package dev.adminos.api.domain.financial.evaluation

import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.domain.ai.AiProviderKind
import dev.adminos.api.domain.ai.eval.AiEvalAnswers
import dev.adminos.api.domain.ai.eval.AiEvalKey
import dev.adminos.api.domain.ai.eval.AiEvalRecord
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.ai.CategorizeMerchantTask

/**
 * The categorisation feature's eval record (AOS-702, generalising D-61): the rules + AI scores on the golden
 * set, in the shape the start-up eval gate reads, with `baseline-ai.json` as the floor. One extra metric,
 * `ruleDecidedUnchanged`, puts D-67's "a model may never change a row the rules decided" in the gate itself:
 * the share of rule-decided rows the AI left alone, floor 1.0.
 */
object CategorizationEvalRecord {
    const val RULE_DECIDED_UNCHANGED = "ruleDecidedUnchanged"

    /** The default route: Claude Haiku 5.5 with the current prompt. */
    val DEFAULT_KEY = AiEvalKey(AiFeature.CATEGORIZATION, AiProviderKind.ANTHROPIC, "claude-haiku-5-5", CategorizeMerchantTask.promptVersion)

    fun metrics(report: EvaluationReport, ruleDecidedUnchanged: Double): Map<String, Double> =
        (mapOf("accuracy" to report.accuracy, "subcategoryAccuracy" to report.subcategoryAccuracy, RULE_DECIDED_UNCHANGED to ruleDecidedUnchanged) +
            report.perCategory.filter { it.support > 0 }.associate { "f1.${it.category.name}" to it.f1 }).toSortedMap()

    fun floor(baseline: Baseline): Map<String, Double> =
        (mapOf("accuracy" to baseline.accuracy, "subcategoryAccuracy" to baseline.subcategoryAccuracy, RULE_DECIDED_UNCHANGED to 1.0) +
            baseline.f1.mapKeys { "f1.${it.key}" }).toSortedMap()

    /** The share of rows the rules decided (not Other) whose label the AI left exactly as it was; 1.0 when none. */
    fun ruleDecidedUnchanged(rules: List<Outcome>, withAi: List<Outcome>): Double {
        val after = withAi.associateBy { it.case.id }
        val decided = rules.filter { it.predicted.label.category != TransactionCategory.OTHER }
        if (decided.isEmpty()) return 1.0
        return decided.count { after[it.case.id]?.predicted == it.predicted }.toDouble() / decided.size
    }

    /** Synthetic recordings say so in their header (see `ai-recorded.csv`); `-PrecordAi` writes real ones. */
    fun answersOf(recording: String): AiEvalAnswers =
        if (recording.lineSequence().takeWhile { it.startsWith("#") }.any { "SYNTHETIC" in it }) AiEvalAnswers.RECORDED_SYNTHETIC else AiEvalAnswers.RECORDED_REAL

    fun record(key: AiEvalKey, input: RecordInput): AiEvalRecord = AiEvalRecord(
        feature = key.feature.key, provider = key.provider.key, model = key.model, promptVersion = key.promptVersion,
        answers = input.answers, evaluatedOn = input.evaluatedOn, goldenRows = input.report.total,
        metrics = metrics(input.report, input.ruleDecidedUnchanged), floor = floor(input.floor)
    )

    data class RecordInput(
        val report: EvaluationReport,
        val ruleDecidedUnchanged: Double,
        val floor: Baseline,
        val answers: AiEvalAnswers,
        val evaluatedOn: String
    )
}
