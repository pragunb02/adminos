package dev.adminos.api.domain.ingestion.cash.evaluation

import dev.adminos.api.domain.financial.InMemoryCategoryRuleRepository
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.ingestion.cash.CashCategoryGuesser
import dev.adminos.api.domain.ingestion.cash.CashIntake
import dev.adminos.api.domain.ingestion.cash.CashPhraseParser
import dev.adminos.api.domain.ingestion.cash.CategoryFrom
import dev.adminos.api.domain.ingestion.cash.DraftEntry
import dev.adminos.api.domain.ingestion.cash.PhraseContext
import dev.adminos.api.domain.ingestion.cash.PhraseParse
import dev.adminos.api.domain.ingestion.cash.RuleCashPhraseParser
import dev.adminos.api.domain.ingestion.cash.UnparsedReason
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** The simplest baseline: every number in the text is an entry, in Other, today. */
object NaiveNumbersParser : CashPhraseParser {
    override suspend fun parse(text: String, context: PhraseContext): PhraseParse {
        val entries = Regex("\\d+(?:\\.\\d+)?").findAll(text).mapNotNull { m ->
            BigDecimal(m.value).takeIf { it.signum() > 0 && it <= CashIntake.MAX_AMOUNT && it.stripTrailingZeros().scale() <= 2 }
                ?.let { DraftEntry(it, TransactionCategory.OTHER, null, context.today, m.value, CategoryFrom.NONE) }
        }.toList()
        return PhraseParse(entries, emptyList())
    }
}

/** What a phrase should give: entries as (amount, category, date), and the reasons for what's refused. */
data class ExpectedEntry(val amount: BigDecimal, val category: TransactionCategory, val date: LocalDate)

data class PhraseCase(val id: String, val today: LocalDate, val text: String, val entries: List<ExpectedEntry>, val unparsed: List<UnparsedReason>, val tags: Set<String>)

/** The synthetic eval set (AOS-718): `src/test/resources/cash/phrases.tsv`. */
object PhraseSet {
    const val RESOURCE = "/cash/phrases.tsv"
    val DEFAULT_TODAY: LocalDate = LocalDate.of(2026, 10, 17)

    fun load(resource: String = RESOURCE): List<PhraseCase> =
        parse(PhraseSet::class.java.getResource(resource)?.readText() ?: throw IllegalStateException("phrase set not found: $resource"))

    fun parse(text: String): List<PhraseCase> {
        val lines = text.lines().withIndex().filter { (_, l) -> l.isNotBlank() && !l.startsWith("#") }
        require(lines.firstOrNull()?.value == "id\ttoday\ttext\texpected\tunparsed\ttags") { "phrase set: missing header" }
        val seen = mutableSetOf<String>()
        return lines.drop(1).map { (i, line) ->
            val f = line.split("\t")
            require(f.size == 6) { "phrase set line ${i + 1}: expected 6 tab-separated fields, got ${f.size}" }
            require(seen.add(f[0])) { "phrase set line ${i + 1}: duplicate id ${f[0]}" }
            val today = f[1].takeIf { it.isNotBlank() }?.let(LocalDate::parse) ?: DEFAULT_TODAY
            try {
                PhraseCase(f[0], today, f[2].replace("\\n", "\n"), entries(f[3], today), reasons(f[4]), f[5].split(" ").filter { it.isNotBlank() }.toSet())
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("phrase set line ${i + 1} (${f[0]}): ${e.message}", e)
            }
        }
    }

    private fun entries(field: String, today: LocalDate) = field.split(";").map { it.trim() }.filter { it.isNotEmpty() }.map { e ->
        val (amount, category, date) = e.split(" ").also { require(it.size == 3) { "entry '$e' is not 'amount category date'" } }
        ExpectedEntry(BigDecimal(amount), TransactionCategory.valueOf(category.uppercase()), dateOf(date, today))
    }

    private fun dateOf(s: String, today: LocalDate): LocalDate = when {
        s == "T" -> today
        s.startsWith("T-") -> today.minusDays(s.removePrefix("T-").toLong())
        else -> LocalDate.parse(s)
    }

    private fun reasons(field: String) = field.split(";").map { it.trim() }.filter { it.isNotEmpty() }.map { UnparsedReason.valueOf(it.uppercase()) }
}

@Serializable
data class PhraseMiss(val id: String, val text: String, val expected: String, val got: String)

/** Scores on the phrase set. Entries are matched as (amount, category, date) triples. */
@Serializable
data class PhraseReport(
    val phrases: Int,
    /** Phrases where every entry and every refusal was exactly right. */
    val exact: Double,
    val entryPrecision: Double,
    val entryRecall: Double,
    /** Of expected entries, how many had the right amount (and date) whatever the category. */
    val amountRecall: Double,
    /** Of entries with the right amount and date, how many had the right category. */
    val categoryAccuracy: Double,
    /** Phrases that should refuse something and refused it for the right reason. */
    val refusals: Double,
    val misses: List<PhraseMiss>
)

/** Runs a parser over the set and scores it (AOS-718, D-61 style). */
object PhraseEvaluator {
    private val user: UUID = UUID.fromString("00000000-0000-0000-0000-000000000718")

    fun rules(): CashPhraseParser = RuleCashPhraseParser(CashCategoryGuesser(InMemoryCategoryRuleRepository()))

    fun evaluate(cases: List<PhraseCase> = PhraseSet.load(), parser: CashPhraseParser = rules()): PhraseReport = runBlocking {
        var exact = 0; var tp = 0; var predicted = 0; var expected = 0; var amountHits = 0; var categoryHits = 0
        var refusalCases = 0; var refusalHits = 0
        val misses = mutableListOf<PhraseMiss>()
        for (c in cases) {
            val got = parser.parse(c.text, PhraseContext(user, c.today))
            val gotTriples = got.entries.map { ExpectedEntry(it.amount.stripTrailingZeros(), it.category, it.date) }
            val want = c.entries.map { it.copy(amount = it.amount.stripTrailingZeros()) }
            val matched = multisetOverlap(want, gotTriples)
            tp += matched; predicted += gotTriples.size; expected += want.size
            val wantAmounts = want.map { it.amount to it.date }
            val gotAmounts = gotTriples.map { it.amount to it.date }
            val amountMatched = multisetOverlap(wantAmounts, gotAmounts)
            amountHits += amountMatched
            categoryHits += matched
            val reasonsRight = c.unparsed.sorted() == got.unparsed.map { it.reason }.sorted()
            if (c.unparsed.isNotEmpty()) { refusalCases++; if (reasonsRight) refusalHits++ }
            if (matched == want.size && gotTriples.size == want.size && reasonsRight) exact++
            else misses += PhraseMiss(c.id, c.text, describe(want, c.unparsed), describe(gotTriples, got.unparsed.map { it.reason }))
        }
        PhraseReport(
            phrases = cases.size,
            exact = round(exact.toDouble() / cases.size),
            entryPrecision = round(if (predicted == 0) 1.0 else tp.toDouble() / predicted),
            entryRecall = round(if (expected == 0) 1.0 else tp.toDouble() / expected),
            amountRecall = round(if (expected == 0) 1.0 else amountHits.toDouble() / expected),
            categoryAccuracy = round(if (amountHits == 0) 1.0 else categoryHits.toDouble() / amountHits),
            refusals = round(if (refusalCases == 0) 1.0 else refusalHits.toDouble() / refusalCases),
            misses = misses
        )
    }

    private fun <T> multisetOverlap(a: List<T>, b: List<T>): Int {
        val left = b.groupingBy { it }.eachCount().toMutableMap()
        var n = 0
        for (x in a) { val k = left[x] ?: 0; if (k > 0) { n++; left[x] = k - 1 } }
        return n
    }

    private fun describe(entries: List<ExpectedEntry>, reasons: List<UnparsedReason>) =
        (entries.map { "${it.amount.toPlainString()} ${it.category.name.lowercase()} ${it.date}" } + reasons.map { "!" + it.name.lowercase() }).joinToString("; ").ifEmpty { "nothing" }

    private fun round(x: Double) = Math.round(x * 10_000) / 10_000.0
}

/** The checked-in floor (AOS-718, D-61 style); raised only with `./gradlew -q cashPhraseReport -PwriteBaseline`. */
@Serializable
data class PhraseBaseline(val subject: String, val phrases: Int, val exact: Double, val entryPrecision: Double, val entryRecall: Double,
                          val categoryAccuracy: Double, val refusals: Double) {
    companion object {
        const val RESOURCE = "/cash/baseline.json"
        val json = Json { prettyPrint = true; encodeDefaults = true }
        fun of(r: PhraseReport) = PhraseBaseline("rules", r.phrases, r.exact, r.entryPrecision, r.entryRecall, r.categoryAccuracy, r.refusals)
        fun load(resource: String = RESOURCE): PhraseBaseline =
            json.decodeFromString(serializer(), PhraseBaseline::class.java.getResource(resource)?.readText() ?: throw IllegalStateException("baseline not found: $resource"))
    }
}

/** `./gradlew -q cashPhraseReport` prints the scores and misses; `-PwriteBaseline` records the floor. */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <report.json> [--write-baseline <dir>]" }
    val r = PhraseEvaluator.evaluate()
    println("Cash phrases (AOS-718, synthetic): ${r.phrases} phrases")
    println("  exact ${r.exact} · entry precision ${r.entryPrecision} · entry recall ${r.entryRecall} · amount+date recall ${r.amountRecall}" +
        " · category accuracy ${r.categoryAccuracy} · refusals ${r.refusals}")
    val naive = PhraseEvaluator.evaluate(parser = NaiveNumbersParser)
    println("  baseline (every number is an entry, Other, today): exact ${naive.exact} · precision ${naive.entryPrecision} · recall ${naive.entryRecall}" +
        " · amount+date recall ${naive.amountRecall} · refusals ${naive.refusals}")
    val all = PhraseSet.load()
    for ((name, subset) in listOf("written first (tuned on)" to all.filterNot { "heldout" in it.tags }, "held out" to all.filter { "heldout" in it.tags })) {
        val s = PhraseEvaluator.evaluate(subset)
        println("  $name: ${s.phrases} phrases · exact ${s.exact} · precision ${s.entryPrecision} · recall ${s.entryRecall} · refusals ${s.refusals}")
    }
    r.misses.forEach { println("  MISS ${it.id} \"${it.text.replace("\n", "\\n")}\": want [${it.expected}] got [${it.got}]") }
    val out = File(args[0]).also { it.parentFile?.mkdirs() }
    out.writeText(PhraseBaseline.json.encodeToString(PhraseReport.serializer(), r))
    val i = args.indexOf("--write-baseline")
    if (i >= 0) {
        val dir = File(args.getOrNull(i + 1) ?: error("--write-baseline needs a directory"))
        File(dir, "baseline.json").writeText(PhraseBaseline.json.encodeToString(PhraseBaseline.serializer(), PhraseBaseline.of(r)) + "\n")
        println("Baseline written in ${dir.path}")
    }
}
