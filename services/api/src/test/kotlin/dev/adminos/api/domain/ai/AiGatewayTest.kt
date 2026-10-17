package dev.adminos.api.domain.ai

import dev.adminos.api.domain.scheduling.TestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.YearMonth
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-702, D-67: the gateway's order of checks, the cap, the breaker and the audit row. */
class AiGatewayTest {

    private val now = Instant.parse("2026-10-17T10:00:00Z")
    private val clock = TestClock(now)
    private val user = UUID(0, 1)
    private val ledger = InMemoryAiSpendLedger()
    private val calls = InMemoryAiCallLogRepository()
    private var tick = 0L
    private val consents = AiConsents(InMemoryAiConsentRepository().also { runBlocking { it.save(AiConsent(user, AiFeature.CATEGORIZATION, true, now)) } })

    private fun gateway(provider: AiProvider = FakeAiProvider(), cap: Long = 1_000_000, routes: List<AiRoute>? = null) = AiGateway(
        routes ?: listOf(AiRoute.of(AiFeature.CATEGORIZATION, provider, FakeAiProvider.MODEL)),
        AiCostGuard(ledger, cap, clock), calls, consents, AiCircuitBreaker(clock), clock, nanoTime = { tick.also { tick += 7_000_000 } }
    )

    @Test
    fun `a valid answer is returned, its real cost settled and one audit row written`() = runBlocking<Unit> {
        val result = gateway().run(EchoTask, user, "Steam")
        assertEquals(AiResult.Answered("ok:Steam", FakeAiProvider.MODEL), result)
        // 10 input tokens at $1/MTok + 5 output at $2/MTok = 20 micro-dollars
        assertEquals(20, ledger.spent(YearMonth.of(2026, 10)))
        val row = calls.all.single()
        assertEquals(AiCallRecord(row.id, user, AiFeature.CATEGORIZATION, AiProviderKind.ANTHROPIC, FakeAiProvider.MODEL, "echo-v1",
            AiUsage(10, 5), 20, AiCallOutcome.ACCEPTED, null, 7, now), row)
    }

    @Test
    fun `the request carries the task's prompt and the route's model`() = runBlocking<Unit> {
        val provider = FakeAiProvider()
        gateway(provider).run(EchoTask, user, "Steam")
        val r = provider.requests.single()
        assertEquals(FakeAiProvider.MODEL, r.model)
        assertEquals("Echo the input.", r.system)
        assertEquals("Steam", r.user)
        assertEquals(100, r.maxOutputTokens)
    }

    @Test
    fun `a feature with no route is refused without a call or an audit row`() = runBlocking<Unit> {
        val provider = FakeAiProvider()
        assertEquals(AiResult.Refused(AiRefusal.FEATURE_OFF), gateway(provider, routes = emptyList()).run(EchoTask, user, "Steam"))
        assertEquals(emptyList(), provider.requests)
        assertEquals(emptyList(), calls.all)
        assertNull(gateway(routes = emptyList()).route(AiFeature.CATEGORIZATION))
    }

    @Test
    fun `input the redactor withholds is never sent`() = runBlocking<Unit> {
        val provider = FakeAiProvider()
        assertEquals(AiResult.Refused(AiRefusal.WITHHELD), gateway(provider).run(EchoTask, user, "my secret"))
        assertEquals(emptyList(), provider.requests)
        assertEquals(emptyList(), calls.all)
    }

    @Test
    fun `an invalid, refused or cut-off answer is rejected and audited with its code`() = runBlocking<Unit> {
        val replies = ArrayDeque(listOf<AiCompletion>(
            AiCompletion.Completed("nonsense", AiUsage(10, 5)), AiCompletion.Refused(AiUsage(10, 1)), AiCompletion.Truncated(AiUsage(10, 100))))
        val g = gateway(FakeAiProvider { replies.removeFirst() })
        assertEquals(AiResult.Rejected(AiReasonCode("not_ok")), g.run(EchoTask, user, "a"))
        assertEquals(AiResult.Rejected(AiGateway.REFUSED), g.run(EchoTask, user, "b"))
        assertEquals(AiResult.Rejected(AiGateway.TRUNCATED), g.run(EchoTask, user, "c"))
        assertEquals(listOf("not_ok", "refused", "truncated"), calls.all.map { it.code?.value })
        assertTrue(calls.all.all { it.outcome == AiCallOutcome.REJECTED })
        assertEquals(20 + 12 + 210, ledger.spent(YearMonth.of(2026, 10)), "rejected answers still cost what they used")
    }

    @Test
    fun `a provider failure is unavailable and audited as an error`() = runBlocking<Unit> {
        val g = gateway(FakeAiProvider { AiCompletion.Failed(AiReasonCode("http_503")) })
        assertEquals(AiResult.Unavailable(AiReasonCode("http_503")), g.run(EchoTask, user, "a"))
        assertEquals(AiCallOutcome.ERROR, calls.all.single().outcome)
        assertEquals(0, calls.all.single().costMicros)
    }

    @Test
    fun `three failures in a row open the circuit, which makes no call until it closes`() = runBlocking<Unit> {
        var fail = true
        val provider = FakeAiProvider { if (fail) AiCompletion.Failed(AiReasonCode("network")) else AiCompletion.Completed("ok", AiUsage(1, 1)) }
        val g = gateway(provider)
        repeat(3) { g.run(EchoTask, user, "a") }
        assertEquals(AiResult.Unavailable(AiGateway.CIRCUIT_OPEN), g.run(EchoTask, user, "a"))
        assertEquals(3, provider.requests.size, "no request while open")
        assertEquals(3, calls.all.size, "no audit row without a call")

        fail = false
        clock.advance(java.time.Duration.ofMinutes(15))
        assertEquals(AiResult.Answered("ok:a", FakeAiProvider.MODEL), g.run(EchoTask, user, "a"))
    }

    @Test
    fun `a call whose worst case would pass the cap is not made`() = runBlocking<Unit> {
        val provider = FakeAiProvider()
        // worst case: (15 + 5 + 17 bytes of prompt and schema + 64) × $1/MTok + 100 × $2/MTok = 301 micro-dollars
        val g = gateway(provider, cap = 250)
        assertEquals(AiResult.Refused(AiRefusal.CAP_REACHED), g.run(EchoTask, user, "Steam"))
        assertEquals(emptyList(), provider.requests)
        assertEquals(emptyList(), calls.all)
    }

    @Test
    fun `free local calls are never blocked by the cap`() = runBlocking<Unit> {
        val local = FakeAiProvider(AiProviderKind.OLLAMA, mapOf(FakeAiProvider.MODEL to AiPrice.FREE))
        assertEquals(AiResult.Answered("ok:Steam", FakeAiProvider.MODEL), gateway(local, cap = 0).run(EchoTask, user, "Steam"))
        assertEquals(AiProviderKind.OLLAMA, calls.all.single().provider)
    }

    @Test
    fun `concurrent calls can never spend past the cap`() = runBlocking<Unit> {
        val provider = FakeAiProvider { delay(5); AiCompletion.Completed("ok", AiUsage(50, 100)) } // 250 micro-dollars each, within the worst case
        val worst = AiPrice.usd("1.00", "2.00").worstCaseMicros(AiRequest(FakeAiProvider.MODEL, "Echo the input.", "Steam", EchoTask.SCHEMA, 100))
        val cap = 10 * worst
        val g = gateway(provider, cap = cap)
        val results = (1..50).map { async(Dispatchers.Default) { g.run(EchoTask, user, "Steam") } }.awaitAll()
        val answered = results.count { it is AiResult.Answered }
        assertTrue(answered >= 10, "at least the calls that fit at their worst case run ($answered)")
        assertTrue(ledger.spent(YearMonth.of(2026, 10)) <= cap, "spent ${ledger.spent(YearMonth.of(2026, 10))} > cap $cap")
        assertEquals(answered, provider.requests.size)
        assertEquals(50 - answered, results.count { it == AiResult.Refused(AiRefusal.CAP_REACHED) })
    }

    @Test
    fun `a provider that throws is unavailable, charged its worst case, and still audited (AOS-702 review)`() = runBlocking<Unit> {
        val g = gateway(FakeAiProvider { throw IllegalStateException("adapter bug") })
        assertEquals(AiResult.Unavailable(AiGateway.INTERNAL), g.run(EchoTask, user, "Steam"))
        assertTrue(ledger.spent(YearMonth.of(2026, 10)) > 0, "an unknown cost fails closed")
        val row = calls.all.single()
        assertEquals(AiCallOutcome.ERROR, row.outcome)
        assertEquals(AiGateway.INTERNAL, row.code)
    }

    /** A task whose validator throws [failure]: a bug, or the parser giving up on a hostile reply. */
    private class ThrowingReadTask(private val failure: () -> Throwable) : AiTask<String, String> by EchoTask {
        override fun read(text: String, input: String): AiReading<String> = throw failure()
    }

    @Test
    fun `a validator that throws, even an Error, rejects the answer and is audited (AOS-635)`() = runBlocking<Unit> {
        val failures = listOf<() -> Throwable>({ StackOverflowError() }, { IllegalStateException("bug") }, { OutOfMemoryError("big") })
        for (failure in failures) {
            assertEquals(AiResult.Rejected(AiGateway.READ_FAILED), gateway().run(ThrowingReadTask(failure), user, "Steam"))
        }
        assertEquals(List(3) { AiCallOutcome.REJECTED }, calls.all.map { it.outcome })
        assertEquals(List(3) { AiGateway.READ_FAILED }, calls.all.map { it.code })
        assertEquals(60, ledger.spent(YearMonth.of(2026, 10)), "each call is settled to its real cost")
    }

    @Test
    fun `a hostile deeply nested reply to the real task is rejected, not a crash (AOS-635)`() = runBlocking<Unit> {
        val provider = FakeAiProvider { AiCompletion.Completed("[".repeat(10_000), AiUsage(10, 5)) }
        val question = dev.adminos.api.domain.financial.ai.AiCategoryQuestion(
            "Steam", null, dev.adminos.api.domain.financial.ai.AI_ALLOWED_CATEGORIES)
        val result = gateway(provider).run(dev.adminos.api.domain.financial.ai.CategorizeMerchantTask, user, question)
        assertEquals(AiResult.Rejected(dev.adminos.api.domain.financial.ai.CategorizeMerchantTask.NOT_JSON_OBJECT), result)
        assertEquals(AiCallOutcome.REJECTED, calls.all.single().outcome)
    }

    @Test
    fun `cancellation thrown by a validator is never swallowed`() = runBlocking<Unit> {
        val task = ThrowingReadTask { kotlinx.coroutines.CancellationException("cancelled") }
        assertFailsWith<kotlinx.coroutines.CancellationException> { gateway().run(task, user, "Steam") }
    }

    @Test
    fun `a cancelled call still settles its reservation and is never left reserved`() = runBlocking<Unit> {
        val g = gateway(FakeAiProvider { kotlinx.coroutines.awaitCancellation() })
        val job = launch { g.run(EchoTask, user, "Steam") }
        delay(50); job.cancelAndJoin()
        // Settled at its worst case (the cost is unknown), rather than left reserved for the rest of the month.
        assertTrue(ledger.spent(YearMonth.of(2026, 10)) > 0, "the reservation was settled on cancellation")
    }

    @Test
    fun `routes are one per feature and need a priced model id`() {
        val p = FakeAiProvider()
        val route = AiRoute.of(AiFeature.CATEGORIZATION, p, FakeAiProvider.MODEL)
        assertFailsWith<IllegalArgumentException> { gateway(routes = listOf(route, route)) }
        assertFailsWith<IllegalArgumentException> { AiRoute.of(AiFeature.CATEGORIZATION, p, "unpriced-model") }
        assertFailsWith<IllegalArgumentException> { AiRoute(AiFeature.CATEGORIZATION, p, "Not A Model", AiPrice.FREE) }
    }

    @Test
    fun `a user who hasn't turned the feature on is refused before anything is sent`() = runBlocking<Unit> {
        val provider = FakeAiProvider()
        val stranger = UUID(0, 2)
        assertEquals(AiResult.Refused(AiRefusal.NO_CONSENT), gateway(provider).run(EchoTask, stranger, "Steam"))
        assertEquals(emptyList(), provider.requests)
        assertEquals(emptyList(), calls.all)
        assertEquals(0, ledger.spent(YearMonth.of(2026, 10)))
    }

    @Test
    fun `a consent store that fails makes the call fail, never proceed`() = runBlocking<Unit> {
        val provider = FakeAiProvider()
        val broken = AiGateway(listOf(AiRoute.of(AiFeature.CATEGORIZATION, provider, FakeAiProvider.MODEL)), AiCostGuard(ledger, 1_000_000, clock), calls,
            AiConsentCheck { _, _ -> throw java.io.IOException("database down") })
        assertFailsWith<java.io.IOException> { broken.run(EchoTask, user, "Steam") }
        assertEquals(emptyList(), provider.requests)
    }

    @Test
    fun `consent is off by default and follows the user's latest choice`() = runBlocking<Unit> {
        val repo = InMemoryAiConsentRepository()
        val check = AiConsents(repo)
        assertEquals(false, check.allows(user, AiFeature.CATEGORIZATION))
        repo.save(AiConsent(user, AiFeature.CATEGORIZATION, true, now))
        assertEquals(true, check.allows(user, AiFeature.CATEGORIZATION))
        repo.save(AiConsent(user, AiFeature.CATEGORIZATION, false, now))
        assertEquals(false, check.allows(user, AiFeature.CATEGORIZATION))
        assertEquals(false, check.allows(UUID(0, 99), AiFeature.CATEGORIZATION), "one user's choice never applies to another")
        assertEquals(listOf(AiConsent(user, AiFeature.CATEGORIZATION, false, now)), repo.findAll(user))
    }
}
