package dev.adminos.api.domain.agent.evaluation

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The committed floor for one detector variant on the synthetic streams (AOS-713, D-61 style).
 * Precision and recall may not drop and false alerts per month may not rise; a deliberate change
 * rewrites it with `./gradlew -q anomalyReport -PwriteBaseline`.
 */
@Serializable
data class AnomalyBaseline(val variant: String, val rows: Int, val precision: Double, val recall: Double, val falseAlertsPerMonth: Double) {
    init {
        require(variant.isNotBlank() && rows > 0) { "A baseline names its variant and counts its rows" }
        require(precision in 0.0..1.0 && recall in 0.0..1.0 && falseAlertsPerMonth >= 0) { "Scores are in range" }
    }

    /** What got worse in [report] than this floor, one sentence each. */
    fun regressions(report: AnomalyEvalReport): List<String> = listOfNotNull(
        "precision dropped from $precision to ${report.precision}".takeIf { report.precision < precision - EPSILON },
        "recall dropped from $recall to ${report.recall}".takeIf { report.recall < recall - EPSILON },
        "false alerts per month rose from $falseAlertsPerMonth to ${report.falseAlertsPerMonth}".takeIf { report.falseAlertsPerMonth > falseAlertsPerMonth + EPSILON }
    )

    companion object {
        private const val EPSILON = 1e-9
        fun of(report: AnomalyEvalReport) = AnomalyBaseline(report.variant, report.rows, report.precision, report.recall, report.falseAlertsPerMonth)
    }
}

object AnomalyBaselines {
    const val RESOURCE = "/anomalies/baseline.json"
    val json = Json { prettyPrint = true; encodeDefaults = true }

    fun load(): List<AnomalyBaseline> {
        val text = AnomalyBaselines::class.java.getResource(RESOURCE)?.readText() ?: throw IllegalStateException("baseline not found: $RESOURCE")
        return json.decodeFromString(text)
    }
}

/** Scores every variant on the committed streams. */
fun anomalyReports(rows: List<StreamRow> = SpendingStreams.load()): List<AnomalyEvalReport> = runBlocking {
    AnomalyEvaluator.VARIANTS.map { v -> AnomalyEvaluator.score(v, AnomalyEvaluator.run(rows, v)) }
}

fun formatReport(r: AnomalyEvalReport): String = buildString {
    appendLine("== ${r.variant} ==")
    appendLine("rows ${r.rows}, injected ${r.injected}, alerts ${r.alerts} (${r.truePositives} true)")
    appendLine("precision ${"%.1f".format(r.precision * 100)}%  recall ${"%.1f".format(r.recall * 100)}%  " +
        "false alerts/month ${r.falseAlertsPerMonth}  quiet streams alerts/month ${r.quietStreamAlertsPerMonth}")
    appendLine("amount-rule alerts ${r.amountAlerts} (${r.amountTruePositives} true)")
    appendLine("recall by kind: " + r.perLabel.joinToString(", ") { "${it.label} ${it.alerted}/${it.injected}" })
    appendLine("false alerts by kind of normal: ${r.falseAlertsByTag}")
    appendLine("false alerts by rule: ${r.falseAlertsByRule}")
}

/**
 * `./gradlew -q anomalyReport` (AOS-713): prints old vs new on the synthetic streams and writes the JSON.
 * Args: `<report.json>`, then optionally `--write-baseline <baseline.json>` (with `-PwriteBaseline`) or
 * `--regenerate <streams.csv>` (with `-PregenerateStreams`: rewrites the set from the seeded generator).
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <report.json> [--write-baseline <file>] [--regenerate <csv>]" }
    args.indexOf("--regenerate").takeIf { it >= 0 }?.let { i ->
        val target = File(args.getOrNull(i + 1) ?: error("--regenerate needs a path"))
        SpendingStreams.write(SpendingStreams.generate(), target)
        println("Streams written to ${target.path}: re-run without -PregenerateStreams to score them")
        return
    }
    val reports = anomalyReports()
    reports.forEach { println(formatReport(it)) }
    val out = File(args[0]).also { it.parentFile?.mkdirs() }
    out.writeText(AnomalyBaselines.json.encodeToString(reports))
    println("JSON report: ${out.path}")

    val i = args.indexOf("--write-baseline")
    if (i >= 0) {
        val target = File(args.getOrNull(i + 1) ?: error("--write-baseline needs a path"))
        target.writeText(AnomalyBaselines.json.encodeToString(reports.map(AnomalyBaseline::of)) + "\n")
        println("Baseline written to ${target.path} (commit it with the change that moved the numbers)")
        return
    }
    val floors = AnomalyBaselines.load().associateBy { it.variant }
    for (r in reports) floors[r.variant]?.regressions(r)?.forEach { println("REGRESSION (${r.variant}): $it") }
}
