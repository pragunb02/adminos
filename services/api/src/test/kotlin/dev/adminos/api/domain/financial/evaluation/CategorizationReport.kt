package dev.adminos.api.domain.financial.evaluation

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File

/** The checked-in baseline (AOS-511): the floor the no-regression test holds the categorizer to. */
object Baselines {
    const val RULES = "/categorization/baseline.json"
    val json = Json { prettyPrint = true; encodeDefaults = true }

    fun load(resource: String): Baseline {
        val text = Baselines::class.java.getResource(resource)?.readText() ?: throw IllegalStateException("baseline not found: $resource")
        return json.decodeFromString(Baseline.serializer(), text)
    }
}

/** Evaluates the shipped rules on the golden set. */
fun rulesReport(): EvaluationReport = runBlocking { CategorizationEvaluator.evaluate(PipelineLabeller().labelAll(GoldenSet.load())) }

/**
 * `./gradlew -q categorizationReport` (AOS-511): prints the table and writes the JSON report.
 * Args: `<report.json>` and, only with `-PwriteBaseline`, `--write-baseline <baseline.json>`.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <report.json> [--write-baseline <baseline.json>]" }
    val report = rulesReport()
    println(ReportFormatter.table("Categorization: rules (golden set, synthetic)", report))

    val out = File(args[0]).also { it.parentFile?.mkdirs() }
    out.writeText(Baselines.json.encodeToString(EvaluationReport.serializer(), report))
    println("JSON report: ${out.path}")

    val i = args.indexOf("--write-baseline")
    if (i >= 0) {
        val target = File(args.getOrNull(i + 1) ?: error("--write-baseline needs a path"))
        target.writeText(Baselines.json.encodeToString(Baseline.serializer(), Baseline.of("rules", report)) + "\n")
        println("Baseline written: ${target.path} (commit it with the change that moved the numbers)")
    } else {
        val baseline = Baselines.load(Baselines.RULES)
        RegressionGate.regressions(report, baseline).forEach { println("REGRESSION: $it") }
        RegressionGate.improvements(report, baseline).forEach { println("improved: $it (record with -PwriteBaseline)") }
    }
}
