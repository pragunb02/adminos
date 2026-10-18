package dev.adminos.api.domain.financial.grouping

import dev.adminos.api.domain.financial.MerchantCatalogue
import dev.adminos.api.domain.financial.MerchantKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.roundToLong

/** One labelled pair of the synthetic set (AOS-709): are these two stored names one merchant? */
data class LabelledPair(val id: String, val a: String, val b: String, val upiA: String?, val upiB: String?, val same: Boolean, val tag: String) {
    init {
        require(id.isNotBlank() && a.isNotBlank() && b.isNotBlank() && tag.isNotBlank()) { "A pair has an id, two names and a tag" }
        require(MerchantKey.of(a) != MerchantKey.of(b)) { "$id: identical names are already one merchant" }
    }

    /** Payees that are people, not businesses (P2P): their names merge only on stronger evidence. */
    val people: Boolean get() = tag.startsWith("person") || tag == "vpa-person"
}

object MerchantPairs {
    const val RESOURCE = "/merchants/pairs.csv"
    const val HEADER = "id,name_a,name_b,upi_a,upi_b,same,tag"

    fun load(resource: String = RESOURCE): List<LabelledPair> {
        val text = MerchantPairs::class.java.getResource(resource)?.readText() ?: throw IllegalStateException("pair set not found: $resource")
        require(text.startsWith("# SYNTHETIC")) { "$resource must say it is synthetic" }
        val lines = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        require(lines.first() == HEADER) { "Unexpected header in $resource" }
        return lines.drop(1).map { line ->
            val c = line.split(',')
            require(c.size == 7 && c[5] in setOf("0", "1")) { "Bad row: $line" }
            LabelledPair(c[0], c[1], c[2], c[3].ifBlank { null }, c[4].ifBlank { null }, c[5] == "1", c[6])
        }
    }
}

/** A way to decide a pair, measured on the same set: the shipped matcher, its variants, and plain thresholds. */
data class PairDecider(val name: String, val decide: (MerchantProfile, MerchantProfile) -> Boolean)

@Serializable
data class PairScore(val decider: String, val pairs: Int, val truePositives: Int, val falsePositives: Int, val falseNegatives: Int,
                     val precision: Double, val recall: Double, val falsePositiveIds: List<String>, val missedIds: List<String>)

@Serializable
data class PairBaseline(val decider: String, val pairs: Int, val precision: Double, val recall: Double) {
    fun regressions(s: PairScore): List<String> = listOfNotNull(
        "precision dropped from $precision to ${s.precision}".takeIf { s.precision < precision - 1e-9 },
        "recall dropped from $recall to ${s.recall}".takeIf { s.recall < recall - 1e-9 }
    )

    companion object {
        fun of(s: PairScore) = PairBaseline(s.decider, s.pairs, s.precision, s.recall)
    }
}

/** Pairwise precision and recall of each [PairDecider] on the synthetic pairs (AOS-709, research §12). */
object GroupingEvaluation {
    const val BASELINE = "/merchants/baseline.json"
    val json = Json { prettyPrint = true; encodeDefaults = true }
    private val catalogue = MerchantCatalogue.DEFAULT

    val SHIPPED = PairDecider("matcher: Jaro-Winkler, guarded (shipped)") { a, b -> MerchantMatcher().match(a, b) != null }
    val DECIDERS = listOf(
        PairDecider("exact cleaned name only") { a, b -> a.clean.compact == b.clean.compact },
        PairDecider("Jaro-Winkler >= 0.92 on cleaned names, no guards") { a, b -> JaroWinklerSimilarity.score(a.clean, b.clean) >= 0.92 },
        PairDecider("token-set >= 0.92 on cleaned names, no guards") { a, b -> TokenSetSimilarity.score(a.clean, b.clean) >= 0.92 },
        SHIPPED,
        PairDecider("matcher: token-set, guarded") { a, b -> MerchantMatcher(TokenSetSimilarity).match(a, b) != null },
        PairDecider("matcher: Jaro-Winkler, prefix 2") { a, b -> MerchantMatcher(prefix = 2).match(a, b) != null },
        PairDecider("matcher: Jaro-Winkler, threshold 0.88") { a, b -> MerchantMatcher(threshold = 0.88).match(a, b) != null },
        PairDecider("matcher: Jaro-Winkler, threshold 0.95") { a, b -> MerchantMatcher(threshold = 0.95).match(a, b) != null }
    )

    fun profile(name: String, upi: String?, people: Boolean): MerchantProfile {
        val brand = catalogue.byBrand(name)?.brand
        return MerchantProfile(name = name, key = MerchantKey.of(name) ?: error("blank name"), clean = MerchantNameCleaner.clean(name) ?: error("no words: $name"),
            payments = 1, upiIds = setOfNotNull(upi?.lowercase()), brand = brand, business = brand != null || !people)
    }

    fun score(decider: PairDecider, pairs: List<LabelledPair>): PairScore {
        val said = pairs.associateWith { p -> decider.decide(profile(p.a, p.upiA, p.people), profile(p.b, p.upiB, p.people)) }
        val tp = said.count { (p, yes) -> yes && p.same }
        val fp = said.filter { (p, yes) -> yes && !p.same }.keys.map { it.id }
        val fn = said.filter { (p, yes) -> !yes && p.same }.keys.map { it.id }
        return PairScore(decider.name, pairs.size, tp, fp.size, fn.size, ratio(tp, tp + fp.size), ratio(tp, tp + fn.size), fp, fn)
    }

    fun scores(pairs: List<LabelledPair> = MerchantPairs.load()): List<PairScore> = DECIDERS.map { score(it, pairs) }

    fun baselines(): List<PairBaseline> {
        val text = GroupingEvaluation::class.java.getResource(BASELINE)?.readText() ?: throw IllegalStateException("baseline not found: $BASELINE")
        return json.decodeFromString(text)
    }

    fun format(s: PairScore): String =
        "%-55s precision %5.1f%%  recall %5.1f%%  (TP %d, FP %d %s, missed %d)".format(
            s.decider, s.precision * 100, s.recall * 100, s.truePositives, s.falsePositives, s.falsePositiveIds, s.falseNegatives)

    private fun ratio(n: Int, d: Int) = if (d == 0) 0.0 else (n.toDouble() / d * 10_000).roundToLong() / 10_000.0
}

/**
 * `./gradlew -q merchantGroupingReport` (AOS-709): pair precision/recall of every decider on the synthetic
 * set; `-PwriteBaseline` records them as the floor.
 */
fun main(args: Array<String>) {
    val pairs = MerchantPairs.load()
    val scores = GroupingEvaluation.scores(pairs)
    println("Merchant grouping on ${pairs.size} synthetic pairs (${pairs.count { it.same }} same, ${pairs.count { !it.same }} different)")
    scores.forEach { println(GroupingEvaluation.format(it)) }
    val missed = scores.first { it.decider == GroupingEvaluation.SHIPPED.name }.missedIds.toSet()
    println("Shipped matcher misses: " + pairs.filter { it.id in missed }.joinToString("; ") { "${it.a} / ${it.b} (${it.tag})" })
    if (args.isNotEmpty()) File(args[0]).also { it.parentFile?.mkdirs() }.writeText(GroupingEvaluation.json.encodeToString(scores))
    val i = args.indexOf("--write-baseline")
    if (i >= 0) {
        val target = File(args.getOrNull(i + 1) ?: error("--write-baseline needs a path"))
        target.writeText(GroupingEvaluation.json.encodeToString(scores.map(PairBaseline::of)) + "\n")
        println("Baseline written to ${target.path}")
    }
}
