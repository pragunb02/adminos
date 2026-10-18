package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.coach.CoachFixture
import dev.adminos.api.domain.coach.lenses.LensId
import dev.adminos.api.domain.coach.limits.*
import dev.adminos.api.domain.coach.settings.CoachSettings
import dev.adminos.api.domain.coach.settings.CoachSettingsRepository
import dev.adminos.api.domain.coach.settings.InMemoryCoachSettingsRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionRepository
import dev.adminos.api.domain.notifications.Notification
import dev.adminos.api.domain.notifications.NotificationChannel
import dev.adminos.api.domain.notifications.NotificationType
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V35 on Postgres (AOS-904): limits, the alert ledger, dismissals, settings, first spend; fakes on the same contract. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoachLimitsIntegrationTest {
    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private val at = Instant.parse("2026-11-18T06:30:00Z").truncatedTo(ChronoUnit.MICROS)

    private fun limitsContract(repo: SpendingLimitRepository) = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val other = PostgresTestDatabase.newUser()
        val qc = SpendingLimit(userId = user, scope = LimitScope.Lens(LensId("quick_commerce")), label = "Quick commerce", period = LimitPeriod.WEEKLY,
            amount = BigDecimal("2000.00"), createdAt = at, updatedAt = at)
        repo.save(qc)
        val merchant = repo.save(qc.copy(id = java.util.UUID.randomUUID(), scope = LimitScope.Merchant("Zepto"), label = "Zepto", createdAt = at.plusSeconds(1)))
        repo.save(qc.copy(id = java.util.UUID.randomUUID(), scope = LimitScope.Category(dev.adminos.api.domain.financial.TransactionCategory.FOOD), label = "Food", createdAt = at.plusSeconds(2)))
        assertFailsWith<DuplicateLimitException> { repo.save(qc.copy(id = java.util.UUID.randomUUID())) }
        repo.save(qc.copy(id = java.util.UUID.randomUUID(), userId = other)) // same scope, another user: fine
        assertEquals(listOf(qc, merchant), repo.findByUser(user).take(2))
        assertEquals(3, repo.findByUser(user).size)
        assertNull(repo.findById(other, qc.id))
        assertFailsWith<DuplicateLimitException> { repo.update(merchant.copy(scope = qc.scope, label = qc.label)) }
        repo.update(qc.copy(amount = BigDecimal("2500.00"), period = LimitPeriod.MONTHLY, updatedAt = at.plusSeconds(60)))
        assertEquals(BigDecimal("2500.00"), repo.findById(user, qc.id)?.amount)
        assertFalse(repo.delete(other, qc.id))
        assertTrue(repo.delete(user, qc.id))
        assertEquals(2, repo.findByUser(user).size)
    }

    @Test fun `limits on Postgres`() = limitsContract(ExposedSpendingLimitRepository())
    @Test fun `limits in memory behave the same`() = limitsContract(InMemorySpendingLimitRepository())

    private fun ledgerContract(limits: SpendingLimitRepository, ledger: LimitAlertLedger) = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val l = limits.save(SpendingLimit(userId = user, scope = LimitScope.Lens(LensId("food_delivery")), label = "Food delivery", period = LimitPeriod.WEEKLY,
            amount = BigDecimal("1000.00"), createdAt = at, updatedAt = at))
        val week = LocalDate.of(2026, 11, 16)
        // Ten racing claims for the same threshold: exactly one wins.
        val wins = coroutineScope { (1..10).map { async { ledger.claim(l.id, week, Threshold.MOST, at) } }.awaitAll() }
        assertEquals(1, wins.count { it })
        assertTrue(ledger.claim(l.id, week, Threshold.HALF, at))
        assertTrue(ledger.claim(l.id, week.plusWeeks(1), Threshold.MOST, at), "a new week is a new key")
        assertEquals(setOf(Threshold.HALF, Threshold.MOST), ledger.claimed(l.id, week))
    }

    @Test fun `the alert ledger claims once, even under a race, on Postgres`() = ledgerContract(ExposedSpendingLimitRepository(), ExposedLimitAlertLedger())
    @Test fun `the in-memory ledger behaves the same`() = ledgerContract(InMemorySpendingLimitRepository(), InMemoryLimitAlertLedger())

    private fun dismissalsAndSettings(d: SuggestionDismissals, s: CoachSettingsRepository) = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        d.dismiss(user, LensId("travel"), at)
        d.dismiss(user, LensId("travel"), at.plusSeconds(5))
        assertEquals(mapOf(LensId("travel") to at.plusSeconds(5)), d.dismissedAt(user))
        assertNull(s.find(user))
        s.save(CoachSettings(user, limitAlerts = false, updatedAt = at))
        s.save(CoachSettings(user, limitAlerts = true, updatedAt = at.plusSeconds(1)))
        assertEquals(CoachSettings(user, limitAlerts = true, updatedAt = at.plusSeconds(1)), s.find(user))
    }

    @Test fun `dismissals and settings on Postgres`() = dismissalsAndSettings(ExposedSuggestionDismissals(), ExposedCoachSettingsRepository())
    @Test fun `dismissals and settings in memory`() = dismissalsAndSettings(InMemorySuggestionDismissals(), InMemoryCoachSettingsRepository())

    private fun firstSpend(repo: TransactionRepository, history: SpendHistory) = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        assertNull(history.firstSpendAt(user))
        repo.save(CoachFixture.txn("Acme Payroll", "90000.00", LocalDateTime.of(2026, 9, 1, 9, 0), nature = TransactionNature.INCOME, user = user))
        repo.save(CoachFixture.txn("Zepto", "100.00", LocalDateTime.of(2026, 10, 2, 9, 0), user = user))
        repo.save(CoachFixture.txn("Zepto", "50.00", LocalDateTime.of(2026, 10, 1, 9, 0), nature = TransactionNature.REFUND, user = user))
        assertEquals(LocalDateTime.of(2026, 10, 1, 9, 0).atZone(CoachFixture.IST).toInstant(), history.firstSpendAt(user))
    }

    @Test fun `the first spend comes from Postgres`() = ExposedTransactionRepository().let { firstSpend(it, ExposedSpendHistory()) }
    @Test fun `the first spend in memory is the same`() = InMemoryTransactionRepository().let { firstSpend(it, RepositorySpendHistory(it)) }

    @Test
    fun `a coach notification can be stored with its new type`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val repo = ExposedNotificationRepository()
        repo.save(Notification(userId = user, type = NotificationType.COACH, title = "t", body = "b", channel = NotificationChannel.IN_APP))
        assertEquals(1, repo.countByUserAndType(user, NotificationType.COACH))
    }
}
