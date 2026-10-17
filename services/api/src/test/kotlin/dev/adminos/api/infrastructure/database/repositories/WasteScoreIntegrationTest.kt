package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.*
import dev.adminos.api.domain.financial.mandates.MandateService
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V24 waste reasons and kept factors on Postgres, behaving like the in-memory repository (AOS-509). */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WasteScoreIntegrationTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-10-16T23:30:00Z")

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private val reasons = listOf(
        WasteReason(WasteFactor.DUPLICATE_SERVICE, 35, "You also pay for JioHotstar (both streaming)"),
        WasteReason(WasteFactor.PRICE_INCREASED, 40, "Price went up 25% at the last charge"),
        WasteReason(WasteFactor.USAGE_UNKNOWN, 0, "Usage unknown: AdminOS can't see whether you use it")
    )

    /** The same checks for both implementations (Liskov). */
    private suspend fun roundTrips(repo: SubscriptionRepository, userId: UUID) {
        val s = repo.save(Subscription(userId = userId, name = "Netflix", merchantName = "netflix-509-${UUID.randomUUID()}",
            amount = BigDecimal("649.00"), billingCycle = BillingCycle.MONTHLY))
        val fresh = repo.findById(s.id)!!
        assertEquals(emptyList(), fresh.wasteReasons, "nothing stored is an empty list")
        assertEquals(emptySet(), fresh.wasteFactorsDismissed)

        repo.update(fresh.copy(wasteScore = BigDecimal("0.75"), wasteScoreUpdatedAt = now, wasteReasons = reasons))
        val scored = repo.findById(s.id)!!
        assertEquals(reasons, scored.wasteReasons)
        assertEquals(0, BigDecimal("0.75").compareTo(scored.wasteScore))

        repo.update(scored.keep(now))
        val kept = repo.findById(s.id)!!
        assertEquals(setOf(WasteFactor.DUPLICATE_SERVICE, WasteFactor.PRICE_INCREASED), kept.wasteFactorsDismissed)
        assertEquals(now, kept.flagDismissedAt)

        repo.update(kept.copy(wasteReasons = emptyList(), wasteFactorsDismissed = emptySet()))
        val cleared = repo.findById(s.id)!!
        assertEquals(emptyList(), cleared.wasteReasons)
        assertEquals(emptySet(), cleared.wasteFactorsDismissed)

        val saved = repo.save(Subscription(userId = userId, name = "Spotify", merchantName = "spotify-509-${UUID.randomUUID()}",
            amount = BigDecimal("119.00"), billingCycle = BillingCycle.MONTHLY, wasteReasons = reasons,
            wasteFactorsDismissed = setOf(WasteFactor.LONG_RUNNING)))
        val back = repo.findById(saved.id)!!
        assertEquals(reasons, back.wasteReasons, "save stores them too")
        assertEquals(setOf(WasteFactor.LONG_RUNNING), back.wasteFactorsDismissed)
    }

    @Test
    fun `waste reasons and kept factors round-trip on Postgres`() = runBlocking<Unit> {
        roundTrips(ExposedSubscriptionRepository(), PostgresTestDatabase.newUser())
    }

    @Test
    fun `the in-memory repository behaves the same`() = runBlocking<Unit> {
        roundTrips(InMemorySubscriptionRepository(), UUID.randomUUID())
    }

    @Test
    fun `scoring a user on Postgres stores the score, reasons and flag`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val subs = ExposedSubscriptionRepository()
        val transactions = ExposedTransactionRepository()
        val clock = Clock.fixed(now, ist)
        val service = WasteScoreService(subs, transactions, MandateService(ExposedMandateRepository(), transactions, clock), clock, ist)
        val netflix = subs.save(Subscription(userId = userId, name = "Netflix", merchantName = "netflix", amount = BigDecimal("649.00"),
            billingCycle = BillingCycle.MONTHLY, priceChanged = true, priceChangePct = BigDecimal("25.00")))
        subs.save(Subscription(userId = userId, name = "JioHotstar", merchantName = "jiohotstar", amount = BigDecimal("299.00"),
            billingCycle = BillingCycle.MONTHLY))
        transactions.save(Transaction(userId = userId, type = TransactionType.DEBIT, amount = BigDecimal("40000.00"), merchantName = "Amazon",
            category = TransactionCategory.SHOPPING, transactedAt = now.minus(2, ChronoUnit.DAYS)))

        assertEquals(WasteScoreRun(scored = 2, flagged = 1, failed = 0), service.scoreUser(userId))

        val n = subs.findById(netflix.id)!!
        assertEquals(0, BigDecimal("0.75").compareTo(n.wasteScore))
        assertEquals(listOf(WasteFactor.PRICE_INCREASED, WasteFactor.DUPLICATE_SERVICE, WasteFactor.USAGE_UNKNOWN), n.wasteReasons.map { it.factor })
        assertTrue(n.isFlagged)
        assertEquals("Price went up 25% at the last charge; You also pay for JioHotstar (both streaming)", n.flaggedReason)
        assertEquals(1L, subs.countByUserId(userId, flagged = true))
        assertEquals(LocalDate.of(2026, 10, 17), n.wasteScoreUpdatedAt!!.atZone(ist).toLocalDate())
    }
}
