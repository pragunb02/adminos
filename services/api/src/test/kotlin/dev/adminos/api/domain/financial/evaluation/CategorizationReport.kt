package dev.adminos.api.domain.financial.evaluation

import dev.adminos.api.config.AiCategorizationConfig
import dev.adminos.api.domain.financial.ai.AiCategoryClient
import dev.adminos.api.domain.financial.ai.AiCategoryResult
import dev.adminos.api.infrastructure.ai.AnthropicCategoryClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File

/** The checked-in baselines (AOS-511, AOS-512): the floors the no-regression tests hold each labeller to. */
object Baselines {
    const val RULES = "/categorization/baseline.json"
    const val RULES_AI = "/categorization/baseline-ai.json"
    val json = Json { prettyPrint = true; encodeDefaults = true }

    fun load(resource: String): Baseline {
        val text = Baselines::class.java.getResource(resource)?.readText() ?: throw IllegalStateException("baseline not found: $resource")
        return json.decodeFromString(Baseline.serializer(), text)
    }
}

/** Evaluates the shipped rules on the golden set. */
fun rulesReport(): EvaluationReport = runBlocking { CategorizationEvaluator.evaluate(PipelineLabeller().labelAll(GoldenSet.load())) }

/** Evaluates rules followed by the AI fallback, answering from the recorded table (deterministic, offline). */
fun rulesAiReport(client: AiCategoryClient = RecordedAiClient.load()): EvaluationReport =
    runBlocking { CategorizationEvaluator.evaluate(AiLabeller(client).labelAll(GoldenSet.load())) }

/**
 * `./gradlew -q categorizationReport` (AOS-511, AOS-512): prints both tables and writes the JSON reports.
 * Args: `<report.json>`, then optionally `--write-baseline <dir>` (with `-PwriteBaseline`) or
 * `--record-ai <ai-recorded.csv>` (with `-PrecordAi`: asks the real Claude API, needs ANTHROPIC_API_KEY).
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <report.json> [--write-baseline <dir>] [--record-ai <csv>]" }
    val recordAt = args.indexOf("--record-ai").takeIf { it >= 0 }?.let { File(args.getOrNull(it + 1) ?: error("--record-ai needs a path")) }
    if (recordAt != null) return record(recordAt)

    val rules = rulesReport()
    val withAi = rulesAiReport()
    println(ReportFormatter.table("Categorization: rules (golden set, synthetic)", rules))
    println(ReportFormatter.table("Categorization: rules + AI fallback (recorded answers, synthetic)", withAi))

    val out = File(args[0]).also { it.parentFile?.mkdirs() }
    out.writeText(Baselines.json.encodeToString(EvaluationReport.serializer(), rules))
    val outAi = File(out.parentFile, "report-ai.json")
    outAi.writeText(Baselines.json.encodeToString(EvaluationReport.serializer(), withAi))
    println("JSON reports: ${out.path}, ${outAi.path}")

    val i = args.indexOf("--write-baseline")
    if (i >= 0) {
        val dir = File(args.getOrNull(i + 1) ?: error("--write-baseline needs a directory"))
        File(dir, "baseline.json").writeText(Baselines.json.encodeToString(Baseline.serializer(), Baseline.of("rules", rules)) + "\n")
        File(dir, "baseline-ai.json").writeText(Baselines.json.encodeToString(Baseline.serializer(), Baseline.of("rules+ai", withAi)) + "\n")
        println("Baselines written in ${dir.path} (commit them with the change that moved the numbers)")
        return
    }
    for ((name, report, resource) in listOf(Triple("rules", rules, Baselines.RULES), Triple("rules+ai", withAi, Baselines.RULES_AI))) {
        val baseline = Baselines.load(resource)
        RegressionGate.regressions(report, baseline).forEach { println("REGRESSION ($name): $it") }
        RegressionGate.improvements(report, baseline).forEach { println("improved ($name): $it (record with -PwriteBaseline)") }
    }
}

/** Asks the real model every golden question once and writes its answers as the new recording. */
private fun record(target: File) = runBlocking {
    val key = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() } ?: error("-PrecordAi needs ANTHROPIC_API_KEY in the environment")
    val config = AiCategorizationConfig(key, System.getenv("AI_CATEGORIZATION_MODEL")?.takeIf { it.isNotBlank() } ?: AiCategorizationConfig.DEFAULT_MODEL)
    val real = AnthropicCategoryClient.create(config)
    val rows = mutableListOf<String>()
    val recorder = AiCategoryClient { q ->
        real.ask(q).also { r ->
            when (r) {
                is AiCategoryResult.Answered -> rows += listOf(q.merchant, r.answer.category.name, r.answer.confidence.toString(),
                    r.answer.reason.replace(',', ';')).joinToString(",")
                else -> println("not recorded (${r::class.simpleName}): ${q.merchant}")
            }
        }
    }
    AiLabeller(recorder).labelAll(GoldenSet.load())
    target.writeText("# AOS-512 recorded AI answers, asked of ${config.model} by -PrecordAi. Only these merchant names were sent.\n" +
        "merchant,category,confidence,reason\n" + rows.joinToString("\n") + "\n")
    println("Recorded ${rows.size} answers in ${target.path}; re-run the report and -PwriteBaseline to adopt them")
}
