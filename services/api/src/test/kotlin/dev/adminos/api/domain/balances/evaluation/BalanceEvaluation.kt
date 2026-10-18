package dev.adminos.api.domain.balances.evaluation

import dev.adminos.api.domain.ingestion.sms.NoBalance
import dev.adminos.api.domain.ingestion.sms.SmsBalanceCapture
import dev.adminos.api.domain.ingestion.sms.SmsBalanceGate
import dev.adminos.api.domain.ingestion.sms.SmsMessage
import dev.adminos.api.domain.ingestion.sms.SmsParserRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.math.BigDecimal
import java.time.Instant

/** What an SMS should give: a balance for an account, or the reason it gives none. */
sealed interface Expected {
    data class Balance(val amount: BigDecimal, val hint: String) : Expected
    data class None(val reason: NoBalance) : Expected
}

data class BalanceCase(val id: String, val sender: String, val text: String, val expected: Expected, val tags: Set<String>)

/** The synthetic eval set (AOS-720): `src/test/resources/balances/sms.tsv`. */
object BalanceSet {
    const val RESOURCE = "/balances/sms.tsv"
    private const val HEADER = "id\tsender\ttext\texpected\ttags"

    fun load(resource: String = RESOURCE): List<BalanceCase> =
        parse(BalanceSet::class.java.getResource(resource)?.readText() ?: throw IllegalStateException("balance set not found: $resource"))

    fun parse(text: String): List<BalanceCase> {
        val lines = text.lines().withIndex().filter { (_, l) -> l.isNotBlank() && !l.startsWith("#") }
        require(lines.firstOrNull()?.value == HEADER) { "balance set: missing header" }
        val seen = mutableSetOf<String>()
        return lines.drop(1).map { (i, line) ->
            val f = line.split("\t")
            require(f.size == 5) { "balance set line ${i + 1}: expected 5 tab-separated fields, got ${f.size}" }
            require(seen.add(f[0])) { "balance set line ${i + 1}: duplicate id ${f[0]}" }
            try {
                BalanceCase(f[0], f[1], f[2], expected(f[3]), f[4].split(" ").filter { it.isNotBlank() }.toSet())
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("balance set line ${i + 1} (${f[0]}): ${e.message}", e)
            }
        }
    }

    private fun expected(field: String): Expected =
        if (field.startsWith("!")) Expected.None(NoBalance.valueOf(field.drop(1).uppercase()))
        else field.split(" ").let { require(it.size == 2) { "expected '<amount> <hint>' or '!reason', got '$field'" }; Expected.Balance(BigDecimal(it[0]), it[1]) }
}

/** Anything that reads an SMS into a balance (or a reason): the shipped rules, or the naive baseline. */
fun interface BalanceExtractor {
    fun extract(sender: String, text: String): Expected
}

/** The shipped path: the registry's parse, the trust gate, then the reader (AOS-720). */
object ShippedRules : BalanceExtractor {
    private val registry = SmsParserRegistry()
    private val gate = SmsBalanceGate(registry)
    override fun extract(sender: String, text: String): Expected {
        val message = SmsMessage(sender, text, Instant.parse("2026-10-16T05:00:00Z"))
        return when (val c = gate.read(message, registry.parse(message))) {
            is SmsBalanceCapture.Trusted -> Expected.Balance(c.balance.amount, c.balance.accountHint)
            is SmsBalanceCapture.Skipped -> Expected.None(c.reason)
        }
    }
}

/** The simplest baseline: the first money figure after any "bal", and the first digits after "A/c"/"Acct"/"Card". */
object NaiveBalance : BalanceExtractor {
    private val FIGURE = Regex("(?i)\\bbal\\w*\\W{0,40}?(?:Rs\\.?|INR|₹)\\s*(-?[0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    private val ACCOUNT = Regex("(?i)\\b(?:a/?c|acct|account|card)\\W*[X*x]*(\\d{3,4})\\b")
    override fun extract(sender: String, text: String): Expected {
        val amount = FIGURE.find(text)?.groupValues?.get(1)?.replace(",", "")?.toBigDecimalOrNull()?.setScale(2) ?: return Expected.None(NoBalance.NONE)
        val hint = ACCOUNT.find(text)?.groupValues?.get(1) ?: return Expected.None(NoBalance.NO_ACCOUNT)
        return Expected.Balance(amount, hint)
    }
}

@Serializable
data class BalanceMiss(val id: String, val expected: String, val got: String)

@Serializable
data class BalanceReport(
    val cases: Int,
    /** Cases whose outcome (the balance and account, or the reason for none) is exactly right. */
    val exact: Double,
    /** Of balances given, how many were exactly right (figure and account). */
    val precision: Double,
    /** Of messages with a balance, how many gave exactly it. */
    val recall: Double,
    /** Of messages that must give no balance (cards, wallets, forgeries…), how many gave one: the dangerous error. */
    val falseBalanceRate: Double,
    /** Of messages correctly giving none, how many for the right reason. */
    val reasonAccuracy: Double,
    val misses: List<BalanceMiss>
)

object BalanceEvaluator {
    fun evaluate(cases: List<BalanceCase> = BalanceSet.load(), extractor: BalanceExtractor = ShippedRules): BalanceReport {
        var exact = 0; var given = 0; var givenRight = 0; var positives = 0; var found = 0
        var negatives = 0; var falseBalances = 0; var none = 0; var reasonsRight = 0
        val misses = mutableListOf<BalanceMiss>()
        for (c in cases) {
            val got = extractor.extract(c.sender, c.text)
            val right = same(c.expected, got)
            if (right) exact++ else misses += BalanceMiss(c.id, describe(c.expected), describe(got))
            if (got is Expected.Balance) { given++; if (right) givenRight++ }
            when (c.expected) {
                is Expected.Balance -> { positives++; if (right) found++ }
                is Expected.None -> {
                    negatives++
                    if (got is Expected.Balance) falseBalances++ else { none++; if (right) reasonsRight++ }
                }
            }
        }
        return BalanceReport(
            cases = cases.size, exact = round(exact, cases.size), precision = round(givenRight, given), recall = round(found, positives),
            falseBalanceRate = if (negatives == 0) 0.0 else round(falseBalances, negatives), reasonAccuracy = round(reasonsRight, none), misses = misses
        )
    }

    private fun same(a: Expected, b: Expected) = when {
        a is Expected.Balance && b is Expected.Balance -> a.amount.compareTo(b.amount) == 0 && a.hint == b.hint
        else -> a == b
    }

    private fun describe(e: Expected) = when (e) {
        is Expected.Balance -> "${e.amount.toPlainString()} ${e.hint}"
        is Expected.None -> "!" + e.reason.name.lowercase()
    }

    private fun round(n: Int, d: Int) = if (d == 0) 1.0 else Math.round(n.toDouble() / d * 10_000) / 10_000.0
}

/** The checked-in floor (AOS-720, D-61 style); raised only with `./gradlew -q balanceReport -PwriteBaseline`. */
@Serializable
data class BalanceBaseline(val subject: String, val cases: Int, val exact: Double, val precision: Double, val recall: Double,
                           val falseBalanceRate: Double, val reasonAccuracy: Double) {
    companion object {
        const val RESOURCE = "/balances/baseline.json"
        val json = Json { prettyPrint = true; encodeDefaults = true }
        fun of(r: BalanceReport) = BalanceBaseline("rules", r.cases, r.exact, r.precision, r.recall, r.falseBalanceRate, r.reasonAccuracy)
        fun load(resource: String = RESOURCE): BalanceBaseline =
            json.decodeFromString(serializer(), BalanceBaseline::class.java.getResource(resource)?.readText() ?: throw IllegalStateException("baseline not found: $resource"))
    }
}

/** `./gradlew -q balanceReport` prints the scores and misses; `-PwriteBaseline` records the floor. */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <report.json> [--write-baseline <dir>]" }
    val all = BalanceSet.load()
    val r = BalanceEvaluator.evaluate(all)
    println("SMS balances (AOS-720, synthetic): ${r.cases} messages")
    println("  exact ${r.exact} · precision ${r.precision} · recall ${r.recall} · false balances ${r.falseBalanceRate} · reasons ${r.reasonAccuracy}")
    val naive = BalanceEvaluator.evaluate(all, NaiveBalance)
    println("  baseline (first figure after any 'bal'): exact ${naive.exact} · precision ${naive.precision} · recall ${naive.recall} · false balances ${naive.falseBalanceRate}")
    for ((name, subset) in listOf("written first (tuned on)" to all.filterNot { "heldout" in it.tags }, "held out" to all.filter { "heldout" in it.tags })) {
        val s = BalanceEvaluator.evaluate(subset)
        println("  $name: ${s.cases} messages · exact ${s.exact} · precision ${s.precision} · recall ${s.recall} · false balances ${s.falseBalanceRate}")
    }
    r.misses.forEach { println("  MISS ${it.id}: want [${it.expected}] got [${it.got}]") }
    File(args[0]).also { it.parentFile?.mkdirs() }.writeText(BalanceBaseline.json.encodeToString(BalanceReport.serializer(), r))
    val i = args.indexOf("--write-baseline")
    if (i >= 0) {
        val dir = File(args.getOrNull(i + 1) ?: error("--write-baseline needs a directory"))
        File(dir, "baseline.json").writeText(BalanceBaseline.json.encodeToString(BalanceBaseline.serializer(), BalanceBaseline.of(r)) + "\n")
        println("Baseline written in ${dir.path}")
    }
}
