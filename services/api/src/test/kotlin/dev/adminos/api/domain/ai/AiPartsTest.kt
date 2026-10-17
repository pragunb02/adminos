package dev.adminos.api.domain.ai

import dev.adminos.api.domain.scheduling.TestClock
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-702: the small parts of the AI layer — prices, money, codes, requests, the breaker, the cap and the log. */
class AiPartsTest {

    // ── Prices and money ──

    @Test
    fun `cost is exact micro-dollars, rounded up`() {
        val haiku = AiPrice.usd("0.10", "0.50")
        assertEquals(22, haiku.costMicros(AiUsage(120, 20)))
        assertEquals(1, haiku.costMicros(AiUsage(1, 0)), "a fraction of a micro-dollar rounds up")
        assertEquals(0, haiku.costMicros(AiUsage.NONE))
        assertEquals(0, AiPrice.FREE.costMicros(AiUsage(1_000_000, 1_000_000)))
        assertTrue(AiPrice.FREE.isFree)
        assertFalse(haiku.isFree)
    }

    @Test
    fun `a long prompt is charged at the long-prompt rate`() {
        val tiered = AiPrice.usd("0.10", "0.50").copy(longPrompt = AiPrice.usd("0.50", "2.50"), longPromptFromTokens = 100_000)
        assertEquals(10_000, tiered.costMicros(AiUsage(100_000, 0)))
        assertEquals(50_001, tiered.costMicros(AiUsage(100_002, 0)))
        assertFailsWith<IllegalArgumentException> { AiPrice(-1, 0) }
        assertFailsWith<IllegalArgumentException> { AiPrice(0, 0, longPromptFromTokens = 0) }
        assertFailsWith<IllegalArgumentException> { AiPrice(0, 0, longPrompt = tiered) }
    }

    @Test
    fun `dollars convert to micro-dollars and back exactly`() {
        assertEquals(2_500_000, Micros.ofUsd(" 2.50 "))
        assertEquals("2.50", Micros.toUsd(2_500_000))
        assertEquals("0.00", Micros.toUsd(0))
        assertEquals("0.000123", Micros.toUsd(123))
        assertEquals("12.00", Micros.toUsd(12_000_000))
        assertFailsWith<IllegalArgumentException> { Micros.ofUsd("two") }
        assertFailsWith<IllegalArgumentException> { Micros.ofUsd("-1") }
        assertFailsWith<ArithmeticException> { Micros.ofUsd("0.0000001") }
    }

    @Test
    fun `reason codes can't carry text`() {
        assertEquals("http_429", AiReasonCode("http_429").toString())
        for (bad in listOf("", "Has Caps", "with space", "x".repeat(49), "quote\"", "amount 499")) {
            assertFailsWith<IllegalArgumentException>(bad) { AiReasonCode(bad) }
        }
    }

    @Test
    fun `a request bounds its input tokens by its bytes and never prints its prompt`() {
        val r = AiRequest("m-1", "sys", "₹499 at Steam", EchoTask.SCHEMA, 10)
        assertEquals(3L + "₹499 at Steam".toByteArray().size + EchoTask.SCHEMA.toString().length + 64, r.inputTokenBound)
        assertFalse(r.toString().contains("Steam"))
        assertFailsWith<IllegalArgumentException> { r.copy(model = " ") }
        assertFailsWith<IllegalArgumentException> { r.copy(user = "") }
        assertFailsWith<IllegalArgumentException> { r.copy(maxOutputTokens = 0) }
        assertFailsWith<IllegalArgumentException> { r.copy(maxOutputTokens = AiRequest.MAX_OUTPUT + 1) }
        assertFailsWith<IllegalArgumentException> { AiUsage(-1, 0) }
        assertFalse(AiCompletion.Completed("secret answer", AiUsage.NONE).toString().contains("secret"))
    }

    @Test
    fun `features and providers have stable keys`() {
        assertEquals(AiFeature.CATEGORIZATION, AiFeature.fromKey("categorization"))
        assertNull(AiFeature.fromKey("nope"))
        assertEquals("AI_CATEGORIZATION", AiFeature.CATEGORIZATION.envPrefix)
        assertEquals(AiProviderKind.OLLAMA, AiProviderKind.fromKey("ollama"))
        assertEquals(AiLocality.LOCAL, AiProviderKind.OLLAMA.locality)
        assertEquals(AiLocality.CLOUD, AiProviderKind.ANTHROPIC.locality)
        assertNull(AiProviderKind.fromKey("openai"))
    }

    // ── Circuit breaker (moved from AOS-512) ──

    @Test
    fun `three failures in a row open one provider's circuit for the open period`() {
        val clock = TestClock(Instant.parse("2026-10-17T10:00:00Z"))
        val b = AiCircuitBreaker(clock, failureThreshold = 3, openFor = Duration.ofMinutes(15))
        repeat(2) { b.record(AiProviderKind.ANTHROPIC, failed = true) }
        assertFalse(b.isOpen(AiProviderKind.ANTHROPIC))
        b.record(AiProviderKind.ANTHROPIC, failed = true)
        assertTrue(b.isOpen(AiProviderKind.ANTHROPIC))
        assertFalse(b.isOpen(AiProviderKind.OLLAMA), "circuits are per provider")
        clock.advance(Duration.ofMinutes(15))
        assertFalse(b.isOpen(AiProviderKind.ANTHROPIC))
    }

    @Test
    fun `any real answer resets the failure count`() {
        val b = AiCircuitBreaker(TestClock(Instant.EPOCH), failureThreshold = 3)
        listOf(true, true, false, true, true).forEach { b.record(AiProviderKind.ANTHROPIC, it) }
        assertFalse(b.isOpen(AiProviderKind.ANTHROPIC))
    }

    @Test
    fun `the breaker validates its settings`() {
        assertFailsWith<IllegalArgumentException> { AiCircuitBreaker(failureThreshold = 0) }
        assertFailsWith<IllegalArgumentException> { AiCircuitBreaker(openFor = Duration.ZERO) }
    }

    // ── Cost guard and ledger ──

    @Test
    fun `the cap holds per UTC month and settles into the reserved month`() = runBlocking<Unit> {
        val clock = TestClock(Instant.parse("2026-10-31T23:59:00Z"))
        val ledger = InMemoryAiSpendLedger()
        val guard = AiCostGuard(ledger, capMicros = 100, clock = clock)
        val r = guard.reserve(60) ?: error("fits")
        assertNull(guard.reserve(41), "60 reserved + 41 > 100")
        clock.advance(Duration.ofMinutes(2)) // now November
        guard.settle(r, 30)
        assertEquals(30, ledger.spent(YearMonth.of(2026, 10)))
        assertEquals(0, guard.spentThisMonth())
        assertEquals(YearMonth.of(2026, 11), guard.currentMonth())
        assertTrue(guard.reserve(100) != null, "a new month starts at zero")
    }

    @Test
    fun `the guard and ledger refuse negative amounts`() = runBlocking<Unit> {
        val guard = AiCostGuard(InMemoryAiSpendLedger(), 10)
        assertFailsWith<IllegalArgumentException> { AiCostGuard(InMemoryAiSpendLedger(), -1) }
        assertFailsWith<IllegalArgumentException> { guard.reserve(-1) }
        assertFailsWith<IllegalArgumentException> { guard.settle(AiReservation(YearMonth.of(2026, 10), 1), -1) }
        assertFailsWith<IllegalArgumentException> { AiReservation(YearMonth.of(2026, 10), -1) }
        assertNull(guard.reserve(11))
        assertNull(guard.reserve(11), "refused again, warned once")
    }

    // ── Audit log ──

    @Test
    fun `an audit row holds ids and codes only, and is consistent`() = runBlocking<Unit> {
        val at = Instant.parse("2026-10-17T10:00:00Z")
        val user = UUID(0, 1)
        val row = AiCallRecord(userId = user, feature = AiFeature.CATEGORIZATION, provider = AiProviderKind.ANTHROPIC, model = "claude-haiku-5-5",
            promptVersion = "categorize-merchant-v1", usage = AiUsage(1, 1), costMicros = 5, outcome = AiCallOutcome.ACCEPTED, code = null, latencyMs = 3, at = at)
        assertFailsWith<IllegalArgumentException> { row.copy(code = AiReasonCode("x")) }
        assertFailsWith<IllegalArgumentException> { row.copy(outcome = AiCallOutcome.ERROR) }
        assertFailsWith<IllegalArgumentException> { row.copy(model = "Steam Purchase") }
        assertFailsWith<IllegalArgumentException> { row.copy(costMicros = -1) }
        assertFailsWith<IllegalArgumentException> { row.copy(latencyMs = -1) }

        val log = InMemoryAiCallLogRepository()
        log.record(row)
        log.record(row.copy(id = UUID.randomUUID(), at = at.plus(Duration.ofDays(30)), costMicros = 7))
        log.record(row.copy(id = UUID.randomUUID(), userId = UUID(0, 2), costMicros = 11))
        assertEquals(5, log.spentMicros(user, AiFeature.CATEGORIZATION, YearMonth.of(2026, 10)))
        assertEquals(7, log.spentMicros(user, AiFeature.CATEGORIZATION, YearMonth.of(2026, 11)))
    }
}
