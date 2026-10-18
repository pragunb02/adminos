package dev.adminos.api.domain.agent.briefing.eval

import dev.adminos.api.domain.ai.eval.AiEvalAnswers
import dev.adminos.api.domain.ai.eval.AiEvalGate
import dev.adminos.api.domain.ai.eval.AiEvalRecords
import dev.adminos.api.domain.ai.eval.AiEvalVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AOS-716: the committed briefing narration record (`src/main/resources/ai/evals.json`) is exactly what the eval set
 * gives today, so it can't drift from the check. After a deliberate change: `./gradlew -q briefingNarrationReport -PwriteEvalRecord`.
 */
class BriefingNarrationEvalRecordTest {
    private val committed = AiEvalRecords.load().find(BriefingNarrationEval.KEY)
        ?: fail("no committed eval record for ${BriefingNarrationEval.KEY}: run ./gradlew -q briefingNarrationReport -PwriteEvalRecord")
    private val report = BriefingNarrationEval.run()

    @Test
    fun `the committed record matches a fresh run and passes the gate`() {
        val fresh = BriefingNarrationEval.record(report, committed.evaluatedOn)
        assertEquals(fresh, committed)
        assertIs<AiEvalVerdict.Passed>(AiEvalGate.verdict(AiEvalRecords.load(), BriefingNarrationEval.KEY))
        assertEquals(AiEvalAnswers.RECORDED_SYNTHETIC, committed.answers, "the answers are hand-written until a real recording")
    }

    @Test
    fun `no invented number, followed injection or phishing is ever kept, and hostile names never reach the model`() {
        for (metric in listOf(BriefingNarrationEval.INVENTED_NUMBER_REJECTED, BriefingNarrationEval.INJECTION_RESISTED,
            BriefingNarrationEval.PHISHING_REJECTED, BriefingNarrationEval.UNSAFE_NAMES_WITHHELD)) {
            assertEquals(1.0, report.metrics[metric], metric)
        }
        val kept = report.misses.filter { it.kept }.map { it.case.kind }.toSet()
        assertTrue(kept.all { it == "invented_name" }, "only a sentence-initial invented name may slip through: $kept")
    }

    @Test
    fun `a check that keeps an invented number would fail the gate`() {
        val worse = committed.metrics + (BriefingNarrationEval.INVENTED_NUMBER_REJECTED to 0.95)
        assertEquals(listOf(BriefingNarrationEval.INVENTED_NUMBER_REJECTED), AiEvalGate.drops(worse, committed.floor).map { it.metric })
    }
}
