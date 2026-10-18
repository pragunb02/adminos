package dev.adminos.api.domain.financial

import dev.adminos.api.domain.financial.mandates.InMemoryMandateRepository
import dev.adminos.api.domain.financial.mandates.Mandate
import dev.adminos.api.domain.financial.mandates.MandateFrequency
import dev.adminos.api.domain.financial.mandates.MandateRail
import dev.adminos.api.domain.financial.mandates.MandateService
import dev.adminos.api.domain.financial.mandates.MandateStatus
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.scheduling.JobName
import dev.adminos.api.domain.scheduling.Schedule
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scoring a user's subscriptions from stored data, and the nightly job (AOS-509). */
class WasteScoreServiceTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-10-16T23:30:00Z") // 05:00 IST on 17 Oct
    private val clock = Clock.fixed(now, ist)
    private val subscriptions = InMemorySubscriptionRepository()
    private val transactions = InMemoryTransactionRepository()
    private val mandateRepo = InMemoryMandateRepository()
    private val mandates = MandateService(mandateRepo, transactions, clock)
    private val service = WasteScoreService(subscriptions, transactions, mandates, clock, ist)
    private val userId = UUID.randomUUID()

    private suspend fun sub(name: String, amount: String = "649.00", status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
                            user: UUID = userId, priceChanged: Boolean = false, pct: String? = null) = subscriptions.save(Subscription(
        userId = user, name = name, merchantName = name.lowercase(), amount = BigDecimal(amount), billingCycle = BillingCycle.MONTHLY,
        status = status, priceChanged = priceChanged, priceChangePct = pct?.let(::BigDecimal), firstBilledDate = LocalDate.of(2026, 8, 1)
    ))

    private suspend fun spend(amount: String, daysAgo: Long, user: UUID = userId) = transactions.save(Transaction(
        userId = user, type = TransactionType.DEBIT, amount = BigDecimal(amount), merchantName = "Swiggy",
        paymentMethod = PaymentMethod.UPI, category = TransactionCategory.FOOD, transactedAt = now.minus(daysAgo, ChronoUnit.DAYS)
    ))

    private suspend fun dormantMandate(payee: String) = mandateRepo.save(Mandate(
        userId = userId, rail = MandateRail.UPI_AUTOPAY, payee = payee, amount = BigDecimal("649.00"), frequency = MandateFrequency.MONTHLY,
        status = MandateStatus.ACTIVE, statusChangedAt = now.minus(120, ChronoUnit.DAYS), createdOn = LocalDate.of(2026, 6, 1)
    ))

    @Test
    fun `scores active and trial subscriptions from spending and mandates, and leaves cancelled ones alone`() = runBlocking<Unit> {
        val netflix = sub("Netflix", priceChanged = true, pct = "12.50")
        val hotstar = sub("JioHotstar", amount = "299.00", status = SubscriptionStatus.TRIAL)
        val gone = sub("SonyLIV", status = SubscriptionStatus.CANCELLED)
        spend("4000.00", daysAgo = 3)
        spend("99999.00", daysAgo = 45) // outside the 30 days
        dormantMandate("NETFLIX INDIA")

        val run = service.scoreUser(userId)

        assertEquals(WasteScoreRun(scored = 2, flagged = 1, failed = 0), run)
        val n = subscriptions.findById(netflix.id)!!
        assertEquals(BigDecimal("1.00"), n.wasteScore, "dormant AutoPay 40 + duplicate 35 + price 30 + 16% of spending 25, capped")
        assertEquals(now, n.wasteScoreUpdatedAt)
        assertEquals(listOf(WasteFactor.DORMANT_MANDATE, WasteFactor.DUPLICATE_SERVICE, WasteFactor.PRICE_INCREASED, WasteFactor.HIGH_COST_SHARE,
            WasteFactor.USAGE_UNKNOWN), n.wasteReasons.map { it.factor })
        assertTrue(n.isFlagged)
        assertTrue(n.flaggedReason!!.startsWith("AutoPay for it is still active"))
        val h = subscriptions.findById(hotstar.id)!!
        assertEquals(BigDecimal("0.60"), h.wasteScore, "duplicate 35 + 7% of spending 25")
        assertFalse(h.isFlagged)
        assertNull(subscriptions.findById(gone.id)!!.wasteScore, "cancelled: not scored")
    }

    @Test
    fun `a user with nothing to score is a no-op`() = runBlocking<Unit> {
        sub("Netflix", status = SubscriptionStatus.CANCELLED)
        assertEquals(WasteScoreRun(0, 0, 0), service.scoreUser(userId))
    }

    @Test
    fun `running twice gives the same stored result`() = runBlocking<Unit> {
        val netflix = sub("Netflix", priceChanged = true, pct = "25")
        sub("JioHotstar")
        service.scoreUser(userId)
        val first = subscriptions.findById(netflix.id)!!
        service.scoreUser(userId)
        val second = subscriptions.findById(netflix.id)!!
        assertEquals(first.copy(updatedAt = second.updatedAt), second)
        assertTrue(second.isFlagged, "35 + 40 = 0.75")
    }

    @Test
    fun `one subscription that fails to save doesn't stop the others`() = runBlocking<Unit> {
        val broken = sub("Netflix")
        val fine = sub("Spotify", amount = "119.00")
        val failing = object : SubscriptionRepository by subscriptions {
            override suspend fun update(subscription: Subscription): Subscription =
                if (subscription.id == broken.id) throw IOException("connection reset") else subscriptions.update(subscription)
        }
        val run = WasteScoreService(failing, transactions, mandates, clock, ist).scoreUser(userId)
        assertEquals(WasteScoreRun(scored = 1, flagged = 0, failed = 1), run)
        assertEquals(BigDecimal("0.00"), subscriptions.findById(fine.id)!!.wasteScore)
    }

    @Test
    fun `the job runs daily at 05-00 after detection, for active users, isolating a failing user`() = runBlocking<Unit> {
        val users = InMemoryUserRepository()
        val good = users.save(User(email = "owner@example.com", googleId = "g-waste-1"))
        val broken = users.save(User(email = "other@example.com", googleId = "g-waste-2"))
        users.save(User(email = "off@example.com", googleId = "g-waste-3", isActive = false))
        sub("Netflix", user = good.id, priceChanged = true, pct = "25")
        sub("JioHotstar", user = good.id)
        val failingSpend = object : TransactionRepository by transactions {
            override suspend fun spendingByCategory(userId: UUID, from: Instant, to: Instant): Map<TransactionCategory, BigDecimal> =
                if (userId == broken.id) throw IOException("read timed out") else transactions.spendingByCategory(userId, from, to)
        }
        sub("Netflix", user = broken.id)
        val job = SubscriptionWasteScoreJob(users, WasteScoreService(subscriptions, failingSpend, mandates, clock, ist), ist)

        assertEquals(JobName("subscription-waste-score"), job.name)
        assertEquals(Schedule.Daily(LocalTime.of(5, 0), ist), job.schedule)
        assertEquals(mapOf("users" to 2, "scored" to 2, "flagged" to 1, "failedUsers" to 1, "failedSubscriptions" to 0), job.run().counts)
    }
}
