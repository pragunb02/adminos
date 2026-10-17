package dev.adminos.api.domain.financial.evaluation

import dev.adminos.api.domain.ai.eval.AiEvalAnswers
import dev.adminos.api.domain.ai.eval.AiEvalGate
import dev.adminos.api.domain.ai.eval.AiEvalRecords
import dev.adminos.api.domain.ai.eval.AiEvalVerdict
import dev.adminos.api.domain.financial.TransactionCategory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AOS-702: the categorisation record the start-up eval gate reads (`src/main/resources/ai/evals.json`) is
 * exactly what the golden set gives today, with `baseline-ai.json` as its floor, so it can't drift from the
 * code. If this fails after a deliberate change: `./gradlew -q categorizationReport -PwriteBaseline`.
 */
class CategorizationEvalRecordTest {

    private val committed = AiEvalRecords.load().find(CategorizationEvalRecord.DEFAULT_KEY)
        ?: fail("no committed eval record for ${CategorizationEvalRecord.DEFAULT_KEY}: run ./gradlew -q categorizationReport -PwriteBaseline")
    private val fresh = categorizationEvalRecord(evaluatedOn = committed.evaluatedOn)

    private fun assertClose(expected: Map<String, Double>, actual: Map<String, Double>, what: String) {
        assertEquals(expected.keys, actual.keys, "$what metrics")
        for ((k, v) in expected) assertTrue(abs(v - actual.getValue(k)) < 1e-9, "$what $k: committed ${actual[k]}, now $v")
    }

    @Test
    fun `the committed record matches a fresh run of the golden set and passes the gate`() {
        assertEquals(fresh.goldenRows, committed.goldenRows, "golden set changed size: re-record with -PwriteBaseline")
        assertClose(fresh.metrics, committed.metrics, "score")
        assertClose(fresh.floor, committed.floor, "floor")
        assertEquals(fresh.answers, committed.answers)
        assertIs<AiEvalVerdict.Passed>(AiEvalGate.verdict(AiEvalRecords.load(), CategorizationEvalRecord.DEFAULT_KEY))
    }

    @Test
    fun `the default record says its answers are synthetic`() {
        assertEquals(AiEvalAnswers.RECORDED_SYNTHETIC, committed.answers, "the recording is hand-written until -PrecordAi is run")
        assertEquals(AiEvalAnswers.RECORDED_REAL, CategorizationEvalRecord.answersOf("# recorded by -PrecordAi\nmerchant,category,confidence,reason\n"))
    }

    @Test
    fun `rule-decided rows are measured and a model that changes one fails the gate`() {
        assertEquals(1.0, committed.metrics[CategorizationEvalRecord.RULE_DECIDED_UNCHANGED])
        val rules = rulesOutcomes()
        val flipped = rules.map { o ->
            if (o.predicted.label.category == TransactionCategory.FOOD) o.copy(predicted = o.predicted.copy(label = Label(TransactionCategory.SHOPPING, null))) else o
        }
        val share = CategorizationEvalRecord.ruleDecidedUnchanged(rules, flipped)
        assertTrue(share < 1.0)
        val drops = AiEvalGate.drops(committed.metrics + (CategorizationEvalRecord.RULE_DECIDED_UNCHANGED to share), committed.floor)
        assertEquals(listOf(CategorizationEvalRecord.RULE_DECIDED_UNCHANGED), drops.map { it.metric })
        assertEquals(1.0, CategorizationEvalRecord.ruleDecidedUnchanged(emptyList(), emptyList()))
    }
}
