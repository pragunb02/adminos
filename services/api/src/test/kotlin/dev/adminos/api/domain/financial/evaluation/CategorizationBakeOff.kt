package dev.adminos.api.domain.financial.evaluation

import dev.adminos.api.domain.ai.AiCostGuard
import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.domain.ai.AiGateway
import dev.adminos.api.domain.ai.AiProvider
import dev.adminos.api.domain.ai.AiRoute
import dev.adminos.api.domain.ai.InMemoryAiCallLogRepository
import dev.adminos.api.domain.ai.InMemoryAiSpendLedger
import dev.adminos.api.domain.ai.eval.AiEvalAnswers
import dev.adminos.api.domain.ai.eval.AiEvalGate
import dev.adminos.api.domain.ai.eval.AiEvalKey
import dev.adminos.api.domain.ai.eval.AiEvalRecord
import dev.adminos.api.domain.ai.eval.MetricDrop
import dev.adminos.api.domain.ai.AiProviderKind
import dev.adminos.api.domain.financial.ai.AiCategoryAnswer
import dev.adminos.api.domain.financial.ai.AiCategoryClient
import dev.adminos.api.domain.financial.ai.AiCategoryResult
import dev.adminos.api.domain.financial.ai.CategorizeMerchantTask
import dev.adminos.api.domain.financial.ai.PortAiCategoryClient
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** One labeller's scores in the bake-off. */
data class BakeOffRun(
    val name: String,
    val report: EvaluationReport,
    val ruleDecidedUnchanged: Double,
    /** Questions the model was asked (one per merchant), and how they ended. */
    val asked: Int,
    val answered: Int,
    val rejected: Int,
    val unavailable: Int,
    /** Share of answered merchants where the model chose the same category as the recorded Claude answer; null for the reference itself. */
    val agreementWithClaude: Double?,
    val meanLatencyMs: Long?,
    val p95LatencyMs: Long?,
    /** Metrics below the floor (the rules baseline, D-61); empty means it passes. */
    val drops: List<MetricDrop>,
    /** The model's answers, by merchant, for a recording. */
    val answers: Map<String, AiCategoryAnswer> = emptyMap()
) {
    val passes: Boolean get() = drops.isEmpty()

    /** Share of questions that got a schema-valid answer from the list. */
    val validAnswerRate: Double get() = if (asked == 0) 1.0 else answered.toDouble() / asked
}

/**
 * The local-model bake-off (AOS-703): runs the categorisation golden set through the **real AI port** (gateway,
 * redaction, validation, audit) for each candidate model, and compares it with the rules alone and with the
 * recorded Claude answers. A model passes when it meets the **rules baseline** on every metric and never
 * changes a rule-decided row (D-61: a model-assisted variant must beat or match the rules), and gives a valid
 * answer to at least 90% of questions ([MIN_VALID_ANSWER_RATE]). A passing model's
 * eval record lets it be enabled (D-67, D-68).
 */
class CategorizationBakeOff(
    private val reference: RecordedAiClient = RecordedAiClient.load(),
    private val floor: Baseline = Baselines.load(Baselines.RULES)
) {
    private val golden = GoldenSet.load()
    private val rules = rulesOutcomes()

    /** The rules alone, and rules + the recorded Claude answers, as the rows to beat. */
    suspend fun baselines(): List<BakeOffRun> {
        val alone = score("rules only", rules, Asked(), latencies = emptyList(), agreement = null)
        val claude = Asked()
        val outcomes = AiLabeller(counting(reference, claude)).labelAll(golden)
        return listOf(alone, score("rules + Claude (recorded)", outcomes, claude, latencies = emptyList(), agreement = null))
    }

    /** Rules + [model] on [provider], through the AI gateway. */
    suspend fun run(provider: AiProvider, model: String): BakeOffRun {
        val calls = InMemoryAiCallLogRepository()
        val gateway = AiGateway(listOf(AiRoute.of(AiFeature.CATEGORIZATION, provider, model)), AiCostGuard(InMemoryAiSpendLedger(), FREE_ONLY), calls)
        val asked = Asked()
        val outcomes = AiLabeller(counting(PortAiCategoryClient(gateway), asked)).labelAll(golden)
        return score("rules + ${provider.kind.key} $model", outcomes, asked, calls.all.map { it.latencyMs }, agreement(asked.answers), model = true)
    }

    /** The eval record a passing (or failing) run would commit for [key]. */
    fun record(key: AiEvalKey, run: BakeOffRun, evaluatedOn: String): AiEvalRecord = CategorizationEvalRecord.record(
        key, CategorizationEvalRecord.RecordInput(run.report, run.ruleDecidedUnchanged, floor, AiEvalAnswers.LIVE, evaluatedOn,
            extraMetrics = mapOf(VALID_ANSWER_RATE to run.validAnswerRate), extraFloor = mapOf(VALID_ANSWER_RATE to MIN_VALID_ANSWER_RATE))
    )

    private fun score(name: String, outcomes: List<Outcome>, asked: Asked, latencies: List<Long>, agreement: Double?, model: Boolean = false): BakeOffRun {
        val report = CategorizationEvaluator.evaluate(outcomes)
        val unchanged = CategorizationEvalRecord.ruleDecidedUnchanged(rules, outcomes)
        val sorted = latencies.sorted()
        val valid = if (asked.total == 0) 1.0 else asked.answers.size.toDouble() / asked.total
        val extra = if (model) mapOf(VALID_ANSWER_RATE to valid) else emptyMap()
        val extraFloor = if (model) mapOf(VALID_ANSWER_RATE to MIN_VALID_ANSWER_RATE) else emptyMap()
        return BakeOffRun(
            name = name, report = report, ruleDecidedUnchanged = unchanged,
            asked = asked.total, answered = asked.answers.size, rejected = asked.rejected, unavailable = asked.unavailable,
            agreementWithClaude = agreement,
            meanLatencyMs = sorted.takeIf { it.isNotEmpty() }?.average()?.toLong(),
            p95LatencyMs = sorted.takeIf { it.isNotEmpty() }?.let { it[((it.size - 1) * 95) / 100] },
            drops = AiEvalGate.drops(CategorizationEvalRecord.metrics(report, unchanged) + extra, CategorizationEvalRecord.floor(floor) + extraFloor),
            answers = asked.answers.toMap()
        )
    }

    private fun agreement(answers: Map<String, AiCategoryAnswer>): Double? {
        val comparable = answers.filterKeys { it in reference.merchants }
        if (comparable.isEmpty()) return null
        val same = comparable.count { (merchant, a) -> reference.answerFor(merchant)?.category == a.category }
        return same.toDouble() / comparable.size
    }

    /** What a run asked and how each question ended. */
    private class Asked {
        val answers = ConcurrentHashMap<String, AiCategoryAnswer>()
        private val counts = ConcurrentHashMap<String, Int>()
        val total get() = counts.values.sum()
        val rejected get() = counts["rejected"] ?: 0
        val unavailable get() = counts["unavailable"] ?: 0
        fun count(kind: String) { counts.merge(kind, 1, Int::plus) }
    }

    private fun counting(client: AiCategoryClient, asked: Asked) = AiCategoryClient { user, q ->
        client.ask(user, q).also { r ->
            when (r) {
                is AiCategoryResult.Answered -> { asked.answers[q.merchant] = r.answer; asked.count("answered") }
                is AiCategoryResult.Rejected -> asked.count("rejected")
                is AiCategoryResult.Unavailable -> asked.count("unavailable")
            }
        }
    }

    companion object {
        const val VALID_ANSWER_RATE = "validAnswerRate"

        /**
         * A local model must give a valid answer from the list to at least 90% of questions. Otherwise one that
         * rejects everything would "match the rules" and pass while doing nothing but cost time.
         */
        const val MIN_VALID_ANSWER_RATE = 0.9

        /** Local models are free; a 0 cap proves no cloud call can slip into a bake-off. */
        private const val FREE_ONLY = 0L

        fun key(model: String) = AiEvalKey(AiFeature.CATEGORIZATION, AiProviderKind.OLLAMA, model, CategorizeMerchantTask.promptVersion)

        /** The comparison table: one row per labeller. */
        fun table(runs: List<BakeOffRun>): String {
            fun pct(d: Double) = String.format(Locale.ROOT, "%.1f%%", d * 100)
            val header = listOf("labeller", "accuracy", "macro-F1", "coverage", "by AI", "asked", "ok/rej/unav", "agrees w/ Claude", "mean/p95 ms", "rules kept", "gate")
            val rows = runs.map { r ->
                listOf(
                    r.name, pct(r.report.accuracy), String.format(Locale.ROOT, "%.3f", r.report.macroF1), pct(r.report.coverage),
                    (r.report.bySource["ai"] ?: 0).toString(), r.asked.toString(), "${r.answered}/${r.rejected}/${r.unavailable}",
                    r.agreementWithClaude?.let(::pct) ?: "—",
                    if (r.meanLatencyMs == null) "—" else "${r.meanLatencyMs}/${r.p95LatencyMs}",
                    pct(r.ruleDecidedUnchanged), if (r.passes) "pass" else "FAIL"
                )
            }
            val widths = header.indices.map { i -> (rows.map { it[i] } + header[i]).maxOf { it.length } }
            fun line(cells: List<String>) = cells.mapIndexed { i, c -> c.padEnd(widths[i]) }.joinToString("  ").trimEnd()
            val failures = runs.filter { !it.passes }.flatMap { r -> r.drops.map { "  ${r.name}: $it" } }
            return (listOf(line(header), widths.joinToString("  ") { "-".repeat(it) }) + rows.map(::line) +
                (if (failures.isEmpty()) emptyList() else listOf("", "Below the rules baseline:") + failures)).joinToString("\n")
        }
    }
}
