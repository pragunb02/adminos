package dev.adminos.api.domain.ask.eval

import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.domain.ai.AiProviderKind
import dev.adminos.api.domain.ai.AiReading
import dev.adminos.api.domain.ai.eval.AiEvalAnswers
import dev.adminos.api.domain.ai.eval.AiEvalKey
import dev.adminos.api.domain.ai.eval.AiEvalRecord
import dev.adminos.api.domain.ai.eval.AiEvalRecords
import dev.adminos.api.domain.analytics.AnalyticsService
import dev.adminos.api.domain.analytics.InMemoryAnalyticsRepository
import dev.adminos.api.domain.ask.AskContract
import dev.adminos.api.domain.ask.AskPlan
import dev.adminos.api.domain.ask.AskPlanWire
import dev.adminos.api.domain.ask.AskResultWire
import dev.adminos.api.domain.ask.AskScope
import dev.adminos.api.domain.ask.AskToolOutcome
import dev.adminos.api.domain.ask.AskToolRunner
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * The Ask feature's eval record (AOS-712, D-68): the eval set's scores in the shape the start-up gate reads.
 * The recorded replies are hand-written (`recorded-synthetic`), so the record measures the policy (the
 * vocabulary, the validator, the period resolver and the number check), not Claude; a live run replaces them.
 */
object AskEvalRecord {
    val DEFAULT_KEY = AiEvalKey(AiFeature.ASK, AiProviderKind.ANTHROPIC, AiFeature.ASK.defaultAnthropicModel, AskContract.PROMPT_VERSION)

    fun answersOf(set: AskEvalSet): AiEvalAnswers = if ("SYNTHETIC" in set.note) AiEvalAnswers.RECORDED_SYNTHETIC else AiEvalAnswers.RECORDED_REAL

    fun record(set: AskEvalSet, scores: AskEvalScores, evaluatedOn: String, key: AiEvalKey = DEFAULT_KEY) = AiEvalRecord(
        feature = key.feature.key, provider = key.provider.key, model = key.model, promptVersion = key.promptVersion,
        answers = answersOf(set), evaluatedOn = evaluatedOn, goldenRows = scores.total,
        metrics = scores.metrics.toSortedMap(), floor = AskEvaluator.FLOOR.toSortedMap()
    )
}

/**
 * `./gradlew -q askEvalReport` prints the Ask eval (AOS-712) and writes `build/reports/ask/report.json`.
 * - `-PwriteEvalRecord` records the scores in `src/main/resources/ai/evals.json` for the start-up gate (D-68);
 * - `-PdumpResults` writes each question's expected tool results (as the model would see them) to
 *   `build/reports/ask/results.json`, to write or check recorded answers after the seed changes.
 */
fun main(args: Array<String>) {
    fun arg(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val set = AskEvalSet.load()
    val scores = AskEvaluator.onSeed().evaluate(set)
    println("Ask eval (AOS-712): ${scores.total} questions (${scores.answerable} answerable, ${scores.unanswerable} unanswerable, ${scores.injection} injection), ${scores.answerCases} answers")
    for ((k, v) in scores.metrics) println("  %-20s %.4f   floor %.2f".format(k, v, AskEvaluator.FLOOR[k] ?: 0.0))
    if (scores.misses.isNotEmpty()) println("Misses:\n  " + scores.misses.joinToString("\n  "))
    arg("--out")?.let { out ->
        File(out).apply { parentFile.mkdirs() }.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), JsonObject(
            scores.metrics.mapValues { JsonPrimitive(it.value) } + mapOf("misses" to kotlinx.serialization.json.JsonArray(scores.misses.map(::JsonPrimitive)))
        )) + "\n")
    }
    arg("--dump-results")?.let { File(it).apply { parentFile.mkdirs() }.writeText(dumpResults(set)) }
    arg("--write-eval-records")?.let { path ->
        val file = File(path)
        val record = AskEvalRecord.record(set, scores, evaluatedOn = java.time.LocalDate.now().toString())
        file.writeText(AiEvalRecords.parse(file.readText()).with(record).toJson())
        println("Wrote the ${record.feature} eval record to $path")
    }
}

/** Each answerable question's expected plan, run on the seed, as the model would be shown the results. */
internal fun dumpResults(set: AskEvalSet): String {
    val txns = InMemoryTransactionRepository()
    runBlocking { AskSeed.transactions().forEach { txns.save(it) } }
    val runner = AskToolRunner(AnalyticsService(InMemoryAnalyticsRepository(txns), AskSeed.CLOCK))
    val scope = AskScope(AskSeed.OWNER, AskSeed.ZONE, AskSeed.TODAY)
    val out = set.questions.mapNotNull { q ->
        val plan = (AskPlanWire.read(q.expected.toString()) as? AiReading.Valid)?.value as? AskPlan.Tools ?: return@mapNotNull null
        val results = runBlocking { plan.calls.map { (runner.run(scope, it) as AskToolOutcome.Ran).result } }
        q.id to AskResultWire.forModel(results)
    }
    return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), JsonObject(out.toMap())) + "\n"
}
