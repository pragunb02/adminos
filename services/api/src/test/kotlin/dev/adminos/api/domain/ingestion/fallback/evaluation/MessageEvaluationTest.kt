package dev.adminos.api.domain.ingestion.fallback.evaluation

import dev.adminos.api.domain.ai.eval.AiEvalAnswers
import dev.adminos.api.domain.ai.eval.AiEvalGate
import dev.adminos.api.domain.ai.eval.AiEvalRecords
import dev.adminos.api.domain.ai.eval.AiEvalVerdict
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AOS-705: the synthetic golden set through the fallback (D-130; routing is MessageRoutingTest). Each row's outcome is part of the test (so the set is
 * also a regression test), and the committed eval records must equal a fresh run and pass the gate. After a
 * deliberate change: `./gradlew -q messageReadingReport -PwriteBaseline`.
 */
class MessageEvaluationTest {
    private val graded = MessageEvaluation.run()

    /** What each kind of row must become. */
    private fun wanted(g: Graded): Outcome {
        val tags = g.row.tags
        return when {
            g.row.expected == Expected.NotOffered -> Outcome.NOT_OFFERED
            g.row.expected == Expected.Withheld -> Outcome.WITHHELD
            "hostile" in tags -> Outcome.REJECTED
            "nontransaction" in tags && "mistake" in tags -> Outcome.ACCEPTED // the one error grounding can't see
            "nontransaction" in tags -> Outcome.SAID_NOT
            "mistake" in tags || "unclear" in tags -> Outcome.REJECTED
            else -> Outcome.ACCEPTED
        }
    }

    @Test
    fun `every golden row ends as its kind says`() {
        val wrong = graded.filter { it.outcome != wanted(it) }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n") { "${it.row.id} ${it.row.tags}: ${it.outcome} (${it.code}), wanted ${wanted(it)}" })
        assertTrue(graded.size >= 200, "about 200 messages (research §6), has ${graded.size}")
    }

    @Test
    fun `accepted readings have exactly the expected fields`() {
        val wrong = graded.filter { it.outcome == Outcome.ACCEPTED && "mistake" !in it.row.tags }.filter { g ->
            val e = g.row.expected as? Expected.Transaction ?: return@filter true
            val p = g.proposal ?: return@filter true
            p.amount.compareTo(e.amount) != 0 || p.direction != e.direction || p.accountLast4 != e.last4 || p.merchant != e.merchant ||
                p.occurredAt.atZone(MessageEvaluation.ZONE).toLocalDate() != (e.date ?: MessageEvaluation.receivedOn(g.row))
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n") { "${it.row.id}: ${it.proposal}" })
    }

    @Test
    fun `personal data in a message never reaches the model`() {
        val pii = graded.filter { "pii" in it.row.tags }
        assertEquals(4, pii.size)
        val raw = listOf("9800000012", "98000 00012", "paytm.store77", "test.user@example.com", "ABCDE1234F", "2345 6789 0123", "5021 0042 9988", "12345678", "4455667")
        for (g in pii) {
            val sent = g.sent ?: fail("${g.row.id} was not sent")
            raw.forEach { assertFalse(it in sent, "${g.row.id} sent '$it'") }
        }
    }

    @Test
    fun `hostile rows are never accepted and dropped rows are never asked about`() {
        assertTrue(graded.filter { "hostile" in it.row.tags }.none { it.outcome == Outcome.ACCEPTED })
        assertTrue(graded.filter { it.row.expected == Expected.NotOffered }.all { it.sent == null })
        assertTrue(graded.filter { it.outcome == Outcome.WITHHELD }.all { it.sent == null }, "a withheld message is never sent")
    }

    @Test
    fun `the committed eval records match a fresh run and pass the gate`() {
        val records = AiEvalRecords.load()
        for (key in MessageEvalRecord.KEYS) {
            val committed = records.find(key) ?: fail("no committed eval record for $key: run ./gradlew -q messageReadingReport -PwriteBaseline")
            val fresh = MessageEvalRecord.record(key, graded, committed.evaluatedOn)
            assertEquals(fresh.goldenRows, committed.goldenRows, "golden set changed size: re-record with -PwriteBaseline")
            assertEquals(fresh.metrics.keys, committed.metrics.keys)
            fresh.metrics.forEach { (k, v) -> assertTrue(abs(v - committed.metrics.getValue(k)) < 1e-9, "$key $k: committed ${committed.metrics[k]}, now $v") }
            assertEquals(fresh.floor, committed.floor)
            assertEquals(AiEvalAnswers.RECORDED_SYNTHETIC, committed.answers, "the answers are hand-written until a real model is recorded")
            assertIs<AiEvalVerdict.Passed>(AiEvalGate.verdict(records, key))
        }
    }

    @Test
    fun `the floor keeps the research gate`() {
        val floor = MessageEvalRecord.floor(MessageEvalRecord.metrics(graded))
        assertEquals(0.99, floor["transactionPrecision"])
        assertEquals(0.98, floor["amountExact"])
        assertEquals(1.0, floor["groundedAccepted"])
        assertEquals(1.0, floor["hostileHeld"])
        // A model that accepts one more look-alike drops below the precision floor.
        val metrics = MessageEvalRecord.metrics(graded)
        assertTrue(AiEvalGate.drops(metrics, floor).isEmpty())
        assertEquals(listOf("transactionPrecision"), AiEvalGate.drops(metrics + ("transactionPrecision" to 0.985), floor).map { it.metric })
    }
}
