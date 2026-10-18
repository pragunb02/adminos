package dev.adminos.api.domain.ai

import dev.adminos.api.domain.ai.eval.AiEvalAnswers
import dev.adminos.api.domain.ai.eval.AiEvalRecord
import dev.adminos.api.domain.ai.eval.AiEvalRecords
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.InMemoryAuditRepository
import dev.adminos.api.domain.scheduling.TestClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-704: Settings → AI features — notices, server status, spend, and the user's consent (off by default). */
class AiFeaturesServiceTest {

    private val now = Instant.parse("2026-10-17T10:00:00Z")
    private val clock = TestClock(now)
    private val user = UUID(0, 704)
    private val consents = InMemoryAiConsentRepository()
    private val calls = InMemoryAiCallLogRepository()
    private val auditLog = InMemoryAuditRepository()
    private val audit = AuditService(auditLog, CoroutineScope(Dispatchers.Unconfined))
    private val costs = AiCostGuard(InMemoryAiSpendLedger(), 2_000_000, clock)
    private val record = AiEvalRecord("categorization", "anthropic", FakeAiProvider.MODEL, EchoTask.promptVersion, AiEvalAnswers.RECORDED_SYNTHETIC,
        "2026-10-17", 288, mapOf("accuracy" to 0.9), mapOf("accuracy" to 0.9))

    private fun service(on: Boolean) = AiFeaturesService(
        AiGateway(if (on) listOf(AiRoute.of(AiFeature.CATEGORIZATION, FakeAiProvider(), FakeAiProvider.MODEL)) else emptyList(), costs, calls, AiConsents(consents)),
        mapOf(AiFeature.CATEGORIZATION to EchoTask, AiFeature.ASK to dev.adminos.api.domain.ask.AskPlanTask,
            AiFeature.MESSAGES to dev.adminos.api.domain.ingestion.fallback.ReadMessageTask), consents, calls, AiEvalRecords(listOf(record)), costs, audit, clock
    )

    @Test
    fun `every feature is listed off by default with its notice`() = runBlocking<Unit> {
        val f = service(on = true).list(user).single { it.feature == AiFeature.CATEGORIZATION }
        assertEquals(AiFeature.CATEGORIZATION, f.feature)
        assertFalse(f.enabled)
        assertEquals(EchoTask.notice, f.notice)
        assertEquals(AiServing(AiProviderKind.ANTHROPIC, FakeAiProvider.MODEL, AiFeaturesService.destination(AiProviderKind.ANTHROPIC)), f.serving)
        assertEquals(record, f.evaluation)
        assertEquals(2_000_000, f.monthlyCapMicros)
        assertTrue(f.available)
    }

    @Test
    fun `a feature the server doesn't run is unavailable and can't be turned on, but can be turned off`() = runBlocking<Unit> {
        val s = service(on = false)
        val f = s.list(user).single { it.feature == AiFeature.CATEGORIZATION }
        assertFalse(f.available)
        assertNull(f.serving)
        assertNull(f.evaluation)
        assertFailsWith<AiFeatureError.NotAvailable> { s.setEnabled(user, AiFeature.CATEGORIZATION, true) }
        assertNull(consents.find(user, AiFeature.CATEGORIZATION), "nothing stored for a refused change")
        assertFalse(s.setEnabled(user, AiFeature.CATEGORIZATION, false).enabled)
    }

    @Test
    fun `turning it on stores the choice for that user only and writes consent evidence`() = runBlocking<Unit> {
        val s = service(on = true)
        assertTrue(s.setEnabled(user, AiFeature.CATEGORIZATION, true).enabled)
        assertTrue(s.list(user).single { it.feature == AiFeature.CATEGORIZATION }.enabled)
        assertFalse(s.list(UUID(0, 1)).single { it.feature == AiFeature.CATEGORIZATION }.enabled, "another user's setting is separate")
        val entry = auditLog.findByUserId(user, 10).single()
        assertEquals(AiFeaturesService.CONSENT_ACTION, entry.action)
        assertEquals("false", entry.beforeState?.get("enabled").toString())
        assertEquals("true", entry.afterState?.get("enabled").toString())
        assertEquals("\"categorization\"", entry.metadata?.get("feature").toString())
        assertFalse(s.setEnabled(user, AiFeature.CATEGORIZATION, false).enabled)
        assertEquals(2, auditLog.findByUserId(user, 10).size)
    }

    @Test
    fun `this month's spend is the user's own calls for the feature`() = runBlocking<Unit> {
        calls.record(AiCallRecord(userId = user, feature = AiFeature.CATEGORIZATION, provider = AiProviderKind.ANTHROPIC, model = FakeAiProvider.MODEL,
            promptVersion = "echo-v1", usage = AiUsage(100, 10), costMicros = 37, outcome = AiCallOutcome.ACCEPTED, code = null, latencyMs = 5, at = now))
        assertEquals(37, service(on = true).list(user).single { it.feature == AiFeature.CATEGORIZATION }.spentThisMonthMicros)
        assertEquals(0, service(on = true).list(UUID(0, 1)).single { it.feature == AiFeature.CATEGORIZATION }.spentThisMonthMicros)
    }

    @Test
    fun `every feature needs a task, and local features say the data stays on the server`() {
        assertFailsWith<IllegalArgumentException> {
            AiFeaturesService(AiGateway(emptyList(), costs, calls, AiConsents(consents)), emptyMap(), consents, calls, AiEvalRecords(emptyList()), costs, audit)
        }
        assertTrue("doesn't leave your server" in AiFeaturesService.destination(AiProviderKind.OLLAMA))
        assertTrue("cloud" in AiFeaturesService.destination(AiProviderKind.ANTHROPIC))
        assertFailsWith<IllegalArgumentException> { AiNotice(" ", "p", listOf("a"), listOf("b")) }
        assertFailsWith<IllegalArgumentException> { AiNotice("t", "p", emptyList(), listOf("b")) }
    }
}
