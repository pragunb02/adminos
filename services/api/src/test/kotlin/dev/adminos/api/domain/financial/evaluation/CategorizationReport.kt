package dev.adminos.api.domain.financial.evaluation

import dev.adminos.api.config.AiConfig
import dev.adminos.api.domain.ai.AiCostGuard
import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.domain.ai.AiGateway
import dev.adminos.api.domain.ai.AiRoute
import dev.adminos.api.domain.ai.InMemoryAiCallLogRepository
import dev.adminos.api.domain.ai.InMemoryAiSpendLedger
import dev.adminos.api.domain.ai.eval.AiEvalRecords
import dev.adminos.api.domain.financial.ai.AiCategoryClient
import dev.adminos.api.domain.financial.ai.AiCategoryResult
import dev.adminos.api.domain.financial.ai.PortAiCategoryClient
import dev.adminos.api.infrastructure.ai.AnthropicProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/** The checked-in baselines (AOS-511, AOS-512): the floors the no-regression tests hold each labeller to. */
object Baselines {
    const val RULES = "/categorization/baseline.json"
    const val RULES_AI = "/categorization/baseline-ai.json"
    const val RECORDING = "/categorization/ai-recorded.csv"
    val json = Json { prettyPrint = true; encodeDefaults = true }

    fun load(resource: String): Baseline {
        val text = Baselines::class.java.getResource(resource)?.readText() ?: throw IllegalStateException("baseline not found: $resource")
        return json.decodeFromString(Baseline.serializer(), text)
    }
}

/** The rules' labels on the golden set. */
fun rulesOutcomes(): List<Outcome> = runBlocking { PipelineLabeller().labelAll(GoldenSet.load()) }

/** Rules followed by the AI fallback, answering from [client] (by default the recorded table: deterministic, offline). */
fun rulesAiOutcomes(client: AiCategoryClient = RecordedAiClient.load()): List<Outcome> = runBlocking { AiLabeller(client).labelAll(GoldenSet.load()) }

/** Evaluates the shipped rules on the golden set. */
fun rulesReport(): EvaluationReport = CategorizationEvaluator.evaluate(rulesOutcomes())

/** Evaluates rules followed by the AI fallback, answering from the recorded table (deterministic, offline). */
fun rulesAiReport(client: AiCategoryClient = RecordedAiClient.load()): EvaluationReport = CategorizationEvaluator.evaluate(rulesAiOutcomes(client))

/** The categorisation eval record for the default route, from the recorded answers, with [floor] as its floor. */
fun categorizationEvalRecord(floor: Baseline = Baselines.load(Baselines.RULES_AI), evaluatedOn: String) = CategorizationEvalRecord.record(
    CategorizationEvalRecord.DEFAULT_KEY,
    CategorizationEvalRecord.RecordInput(
        report = rulesAiReport(),
        ruleDecidedUnchanged = CategorizationEvalRecord.ruleDecidedUnchanged(rulesOutcomes(), rulesAiOutcomes()),
        floor = floor,
        answers = CategorizationEvalRecord.answersOf(RecordedAiClient::class.java.getResource(Baselines.RECORDING)?.readText().orEmpty()),
        evaluatedOn = evaluatedOn
    )
)

/**
 * `./gradlew -q categorizationReport` (AOS-511, AOS-512, AOS-702): prints both tables and writes the JSON reports.
 * Args: `<report.json>`, then optionally `--write-baseline <dir> --eval-records <evals.json>` (with
 * `-PwriteBaseline`: also refreshes the categorisation record the start-up eval gate reads) or
 * `--record-ai <ai-recorded.csv>` (with `-PrecordAi`: asks the real Claude API, needs ANTHROPIC_API_KEY).
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <report.json> [--write-baseline <dir> --eval-records <evals.json>] [--record-ai <csv>]" }
    fun arg(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { File(args.getOrNull(it + 1) ?: error("$name needs a path")) }
    arg("--record-ai")?.let { return record(it) }

    val rules = rulesReport()
    val withAi = rulesAiReport()
    println(ReportFormatter.table("Categorization: rules (golden set, synthetic)", rules))
    println(ReportFormatter.table("Categorization: rules + AI fallback (recorded answers)", withAi))

    val out = File(args[0]).also { it.parentFile?.mkdirs() }
    out.writeText(Baselines.json.encodeToString(EvaluationReport.serializer(), rules))
    val outAi = File(out.parentFile, "report-ai.json")
    outAi.writeText(Baselines.json.encodeToString(EvaluationReport.serializer(), withAi))
    println("JSON reports: ${out.path}, ${outAi.path}")

    val dir = arg("--write-baseline")
    if (dir != null) {
        val aiBaseline = Baseline.of("rules+ai", withAi)
        File(dir, "baseline.json").writeText(Baselines.json.encodeToString(Baseline.serializer(), Baseline.of("rules", rules)) + "\n")
        File(dir, "baseline-ai.json").writeText(Baselines.json.encodeToString(Baseline.serializer(), aiBaseline) + "\n")
        arg("--eval-records")?.let { file ->
            val record = categorizationEvalRecord(aiBaseline, LocalDate.now(ZoneOffset.UTC).toString())
            file.writeText(AiEvalRecords.parse(file.readText()).with(record).toJson())
            println("Eval record for ${record.feature} on ${record.provider} ${record.model} (${record.promptVersion}, ${record.answers}) written in ${file.path}")
        }
        println("Baselines written in ${dir.path} (commit them with the change that moved the numbers)")
        return
    }
    for ((name, report, resource) in listOf(Triple("rules", rules, Baselines.RULES), Triple("rules+ai", withAi, Baselines.RULES_AI))) {
        val baseline = Baselines.load(resource)
        RegressionGate.regressions(report, baseline).forEach { println("REGRESSION ($name): $it") }
        RegressionGate.improvements(report, baseline).forEach { println("improved ($name): $it (record with -PwriteBaseline)") }
    }
}

/**
 * Asks the real model every golden question once, through the AI gateway (so the questions are redacted,
 * capped and audited like production), and writes its answers as the new recording.
 */
private fun record(target: File) = runBlocking {
    val env = System.getenv()
    val key = env["ANTHROPIC_API_KEY"]?.takeIf { it.isNotBlank() } ?: error("-PrecordAi needs ANTHROPIC_API_KEY in the environment")
    val model = env["AI_CATEGORIZATION_MODEL"]?.takeIf { it.isNotBlank() } ?: AiConfig.DEFAULT_ANTHROPIC_MODEL
    val config = AiConfig.from(mapOf("AI_CATEGORIZATION_ENABLED" to "true", "ANTHROPIC_API_KEY" to key, "AI_CATEGORIZATION_MODEL" to model,
        "AI_MONTHLY_CAP_USD" to (env["AI_MONTHLY_CAP_USD"] ?: AiConfig.DEFAULT_CAP_USD))::get)
    val route = AiRoute.of(AiFeature.CATEGORIZATION, AnthropicProvider.create(key), model)
    val real = PortAiCategoryClient(AiGateway(listOf(route), AiCostGuard(InMemoryAiSpendLedger(), config.monthlyCapMicros), InMemoryAiCallLogRepository()))
    val rows = mutableListOf<String>()
    val recorder = AiCategoryClient { user, q ->
        real.ask(user, q).also { r ->
            when (r) {
                is AiCategoryResult.Answered -> rows += listOf(q.merchant, r.answer.category.name, r.answer.confidence.toString(),
                    r.answer.reason.replace(',', ';')).joinToString(",")
                else -> println("not recorded (${r::class.simpleName}): ${q.merchant}")
            }
        }
    }
    AiLabeller(recorder).labelAll(GoldenSet.load())
    target.writeText("# AOS-512 recorded AI answers, asked of $model by -PrecordAi. Only these merchant names were sent.\n" +
        "merchant,category,confidence,reason\n" + rows.joinToString("\n") + "\n")
    println("Recorded ${rows.size} answers in ${target.path}; re-run the report and -PwriteBaseline to adopt them")
}
