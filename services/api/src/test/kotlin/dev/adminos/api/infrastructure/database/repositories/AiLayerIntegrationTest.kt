package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.ai.AiCallLogRepository
import dev.adminos.api.domain.ai.AiCallOutcome
import dev.adminos.api.domain.ai.AiCallRecord
import dev.adminos.api.domain.ai.AiCompletion
import dev.adminos.api.domain.ai.AiCostGuard
import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.domain.ai.AiGateway
import dev.adminos.api.domain.ai.AiProviderKind
import dev.adminos.api.domain.ai.AiReasonCode
import dev.adminos.api.domain.ai.AiResult
import dev.adminos.api.domain.ai.AiRoute
import dev.adminos.api.domain.ai.AiSpendLedger
import dev.adminos.api.domain.ai.AiUsage
import dev.adminos.api.domain.ai.EchoTask
import dev.adminos.api.domain.ai.FakeAiProvider
import dev.adminos.api.domain.ai.InMemoryAiCallLogRepository
import dev.adminos.api.domain.ai.InMemoryAiSpendLedger
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.YearMonth
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-702 on Postgres: the audit log and the spend ledger behave like their in-memory twins, and the cap holds under concurrency. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiLayerIntegrationTest {

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    /** A month no other test touches (the gateway test uses the real current month). */
    private val years = AtomicInteger(2001)
    private fun freshMonth(): YearMonth = YearMonth.of(years.getAndIncrement(), 1)

    private suspend fun ledgerContract(ledger: AiSpendLedger) {
        val month = freshMonth()
        assertEquals(0, ledger.spent(month))
        assertTrue(ledger.reserve(month, 60, capMicros = 100))
        assertFalse(ledger.reserve(month, 41, capMicros = 100), "60 reserved + 41 > 100")
        ledger.settle(month, reservedMicros = 60, actualMicros = 25)
        assertEquals(25, ledger.spent(month))
        assertTrue(ledger.reserve(month, 75, capMicros = 100), "25 spent + 75 = 100 fits")
        assertFalse(ledger.reserve(month, 1, capMicros = 100))
        ledger.settle(month, reservedMicros = 75, actualMicros = 0)
        assertTrue(ledger.reserve(month, 75, capMicros = 100))
    }

    @Test
    fun `the spend ledger reserves against the cap and settles like the in-memory one`() = runBlocking<Unit> {
        ledgerContract(ExposedAiSpendLedger())
        ledgerContract(InMemoryAiSpendLedger())
    }

    @Test
    fun `concurrent reservations on Postgres never pass the cap`() = runBlocking<Unit> {
        val ledger = ExposedAiSpendLedger()
        val month = freshMonth()
        val granted = (1..40).map { async(Dispatchers.IO) { ledger.reserve(month, 10, capMicros = 95) } }.awaitAll().count { it }
        assertEquals(9, granted)
    }

    private suspend fun logContract(log: AiCallLogRepository, user: UUID) {
        val month = YearMonth.of(2026, 10)
        val at = Instant.parse("2026-10-31T23:59:59Z")
        val base = AiCallRecord(userId = user, feature = AiFeature.CATEGORIZATION, provider = AiProviderKind.ANTHROPIC, model = "claude-haiku-5-5",
            promptVersion = "categorize-merchant-v1", usage = AiUsage(120, 20), costMicros = 22, outcome = AiCallOutcome.ACCEPTED,
            code = null, latencyMs = 840, at = at)
        log.record(base)
        log.record(base.copy(id = UUID.randomUUID(), outcome = AiCallOutcome.REJECTED, code = AiReasonCode("bad_reason"), costMicros = 3))
        log.record(base.copy(id = UUID.randomUUID(), at = Instant.parse("2026-11-01T00:00:00Z"), costMicros = 100))
        assertEquals(25, log.spentMicros(user, AiFeature.CATEGORIZATION, month))
        assertEquals(100, log.spentMicros(user, AiFeature.CATEGORIZATION, month.plusMonths(1)))
        assertEquals(0, log.spentMicros(UUID.randomUUID(), AiFeature.CATEGORIZATION, month))
    }

    @Test
    fun `the audit log stores calls and sums a user's monthly spend like the in-memory one`() = runBlocking<Unit> {
        logContract(ExposedAiCallLogRepository(), PostgresTestDatabase.newUser())
        logContract(InMemoryAiCallLogRepository(), UUID.randomUUID())
    }

    @Test
    fun `a gateway call on Postgres settles its cost and writes its audit row`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val log = ExposedAiCallLogRepository()
        val provider = FakeAiProvider { AiCompletion.Completed("ok", AiUsage(10, 5)) }
        val guard = AiCostGuard(ExposedAiSpendLedger(), capMicros = 1_000_000_000)
        val before = guard.spentThisMonth()
        val gateway = AiGateway(listOf(AiRoute.of(AiFeature.CATEGORIZATION, provider, FakeAiProvider.MODEL)), guard, log)
        assertEquals(AiResult.Answered("ok:Steam", FakeAiProvider.MODEL), gateway.run(EchoTask, user, "Steam"))
        assertEquals(before + 20, guard.spentThisMonth())
        assertEquals(20, log.spentMicros(user, AiFeature.CATEGORIZATION, guard.currentMonth()))
    }
}
