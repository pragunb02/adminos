package dev.adminos.api.domain.financial.evaluation

import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.domain.ai.AiProviderKind
import dev.adminos.api.domain.ai.eval.AiEvalAnswers
import dev.adminos.api.domain.ai.eval.AiEvalGate
import dev.adminos.api.domain.ai.eval.AiEvalRecords
import dev.adminos.api.domain.ai.eval.AiEvalVerdict
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * AOS-703: every committed local-model eval record (from `aiBakeOff -PwriteEvalRecord`) is backed by that
 * model's answers in `categorization/bakeoff/<model>.csv`. Replaying them on today's golden set and rules
 * gives the committed scores, so a rule or golden-set change can't silently invalidate a local model's record.
 */
class LocalModelRecordsTest {

    // Categorisation records only: other features (AOS-705 message reading) have their own local records and replay tests.
    private val local = AiEvalRecords.load().records.filter { it.provider == AiProviderKind.OLLAMA.key && it.feature == AiFeature.CATEGORIZATION.key }

    private fun resourceFor(model: String) = "/categorization/bakeoff/" + model.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".csv"

    @Test
    fun `the recommended local models have passing records`() {
        assertEquals(setOf("qwen3:4b", "gemma3:4b"), local.map { it.model }.toSet())
        for (r in local) {
            assertEquals(AiEvalAnswers.LIVE, r.answers)
            assertIs<AiEvalVerdict.Passed>(AiEvalGate.verdict(AiEvalRecords.load(), r.key), r.model)
        }
    }

    @Test
    fun `replaying each local model's recorded answers reproduces its committed scores`() {
        val rules = rulesOutcomes()
        for (record in local) {
            val answers = RecordedAiClient.load(resourceFor(record.model))
            val outcomes = rulesAiOutcomes(answers)
            val report = CategorizationEvaluator.evaluate(outcomes)
            val metrics = CategorizationEvalRecord.metrics(report, CategorizationEvalRecord.ruleDecidedUnchanged(rules, outcomes))
            for ((name, value) in metrics) {
                val committed = record.metrics[name] ?: error("${record.model}: $name missing from the committed record")
                assertTrue(abs(committed - value) < 1e-9, "${record.model} $name: committed $committed, replay $value (re-run aiBakeOff -PwriteEvalRecord)")
            }
            assertTrue((record.metrics[CategorizationBakeOff.VALID_ANSWER_RATE] ?: 0.0) >= CategorizationBakeOff.MIN_VALID_ANSWER_RATE)
            assertEquals(Baselines.load(Baselines.RULES).accuracy, record.floor["accuracy"], "local models are held to the rules baseline (D-61)")
        }
    }
}
