package dev.adminos.api.domain.financial

import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

/** SubscriptionDetectorService on a fixed clock, 16 Oct 2026 (AOS-312). */
class FinancialSubscriptionDetectorCoverageTest {

    private val userId = UUID.randomUUID()
    private val now = Instant.parse("2026-10-16T06:30:00Z")
    private val transactions = InMemoryTransactionRepository()
    private val subscriptions = InMemorySubscriptionRepository()
    private val detector = SubscriptionDetectorService(transactions, subscriptions, Clock.fixed(now, ZoneOffset.UTC))

    private fun day(daysAgo: Long): Instant = now.minusSeconds(daysAgo * 86_400)

    private suspend fun charge(merchant: String?, amount: String, daysAgo: Long, owner: UUID = userId,
                               type: TransactionType = TransactionType.DEBIT, source: String = "sms") =
        transactions.save(Transaction(userId = owner, type = type, amount = BigDecimal(amount), merchantName = merchant,
            paymentMethod = PaymentMethod.CARD_CREDIT, sourceType = source, transactedAt = day(daysAgo)))

    @Test
    fun `three charges a month apart are a monthly subscription named after the merchant`() = runBlocking<Unit> {
        val ids = listOf(65L, 35L, 5L).map { charge("Netflix", "649.00", it, source = "gmail").id }
        val sub = detector.detectSubscriptions(userId).single()
        assertEquals(BillingCycle.MONTHLY, sub.billingCycle)
        assertEquals("Netflix", sub.name)
        assertEquals("netflix", sub.merchantName)
        assertEquals(0, BigDecimal("649.00").compareTo(sub.amount))
        assertEquals(LocalDate.of(2026, 8, 12), sub.firstBilledDate)
        assertEquals(LocalDate.of(2026, 10, 11), sub.lastBilledDate)
        assertEquals(LocalDate.of(2026, 11, 10), sub.nextBillingDate)
        assertEquals("gmail", sub.detectionSource)
        assertEquals(ids, sub.transactionIds)
        assertFalse(sub.priceChanged)
        assertNull(sub.priceChangePct)
        assertEquals(listOf(sub), subscriptions.findByUserId(userId))
    }

    @Test
    fun `weekly charges are a weekly subscription`() = runBlocking<Unit> {
        listOf(22L, 15L, 8L, 1L).forEach { charge("Cult.fit", "199.00", it) }
        assertEquals(BillingCycle.WEEKLY, detector.detectSubscriptions(userId).single().billingCycle)
    }

    @Test
    fun `two charges about a quarter apart are a quarterly subscription`() = runBlocking<Unit> {
        charge("Disney+ Hotstar", "899.00", 88)
        charge("Disney+ Hotstar", "899.00", 1)
        val sub = detector.detectSubscriptions(userId).single()
        assertEquals(BillingCycle.QUARTERLY, sub.billingCycle)
        assertEquals(sub.lastBilledDate!!.plusDays(90), sub.nextBillingDate)
    }

    @Test
    fun `a price rise of more than 5 percent is flagged with its percentage`() = runBlocking<Unit> {
        charge("Spotify", "119.00", 62); charge("Spotify", "119.00", 32); charge("Spotify", "139.00", 2)
        val sub = detector.detectSubscriptions(userId).single()
        assertTrue(sub.priceChanged)
        assertEquals(BigDecimal("16.81"), sub.priceChangePct)
        assertEquals(0, BigDecimal("139.00").compareTo(sub.amount))
    }

    @Test
    fun `a change of 5 percent or less is not a price change`() = runBlocking<Unit> {
        charge("Airtel Xstream", "149.00", 40); charge("Airtel Xstream", "155.00", 10)
        val sub = detector.detectSubscriptions(userId).single()
        assertFalse(sub.priceChanged)
        assertNull(sub.priceChangePct)
    }

    @Test
    fun `a free first month does not count as a price change`() = runBlocking<Unit> {
        charge("YouTube Premium", "0.00", 40); charge("YouTube Premium", "129.00", 10)
        val sub = detector.detectSubscriptions(userId).single()
        assertFalse(sub.priceChanged)
    }

    @Test
    fun `irregular payments to the same merchant are not a subscription`() = runBlocking<Unit> {
        listOf(80L, 61L, 12L, 9L).forEach { charge("Swiggy", "350.00", it) }
        assertTrue(detector.detectSubscriptions(userId).isEmpty())
        assertTrue(subscriptions.findByUserId(userId).isEmpty())
    }

    @Test
    fun `a single charge, a blank merchant and an unnamed payee are skipped`() = runBlocking<Unit> {
        charge("Zee5", "99.00", 20)
        listOf(60L, 30L).forEach { charge("  ", "500.00", it) }
        listOf(61L, 31L).forEach { charge(null, "500.00", it) }
        assertTrue(detector.detectSubscriptions(userId).isEmpty())
    }

    @Test
    fun `charges older than 90 days, other users' charges and refunds are not considered`() = runBlocking<Unit> {
        charge("Netflix", "649.00", 120); charge("Netflix", "649.00", 10)
        listOf(60L, 30L).forEach { charge("Spotify", "119.00", it, owner = UUID.randomUUID()) }
        listOf(60L, 30L).forEach { charge("Amazon", "499.00", it, type = TransactionType.REFUND) }
        assertTrue(detector.detectSubscriptions(userId).isEmpty())
    }

    @Test
    fun `a known subscription is updated in place and reactivated`() = runBlocking<Unit> {
        val known = subscriptions.save(Subscription(userId = userId, name = "Netflix", merchantName = "netflix", amount = BigDecimal("499.00"),
            billingCycle = BillingCycle.MONTHLY, status = SubscriptionStatus.CANCELLED, firstBilledDate = LocalDate.of(2025, 1, 5)))
        charge("Netflix", "649.00", 40); charge("Netflix", "649.00", 10)
        val sub = detector.detectSubscriptions(userId).single()
        assertEquals(known.id, sub.id)
        assertEquals(SubscriptionStatus.ACTIVE, sub.status)
        assertEquals(0, BigDecimal("649.00").compareTo(sub.amount))
        assertEquals(LocalDate.of(2025, 1, 5), sub.firstBilledDate)
        assertEquals(LocalDate.of(2026, 10, 6), sub.lastBilledDate)
        assertEquals(1, subscriptions.countByUserId(userId))
    }

    @Test
    fun `billing cycles are recognised only when intervals are steady`() {
        assertEquals(BillingCycle.WEEKLY, SubscriptionDetectorService.determineBillingCycle(7.0, 0.5))
        assertNull(SubscriptionDetectorService.determineBillingCycle(7.0, 2.5))
        assertEquals(BillingCycle.MONTHLY, SubscriptionDetectorService.determineBillingCycle(30.0, 1.0))
        assertNull(SubscriptionDetectorService.determineBillingCycle(30.0, 6.0))
        assertEquals(BillingCycle.QUARTERLY, SubscriptionDetectorService.determineBillingCycle(91.0, 3.0))
        assertNull(SubscriptionDetectorService.determineBillingCycle(91.0, 11.0))
        assertEquals(BillingCycle.YEARLY, SubscriptionDetectorService.determineBillingCycle(365.0, 2.0))
        assertNull(SubscriptionDetectorService.determineBillingCycle(365.0, 20.0))
        assertNull(SubscriptionDetectorService.determineBillingCycle(3.0, 0.0))
        assertNull(SubscriptionDetectorService.determineBillingCycle(400.0, 0.0))
    }

    @Test
    fun `each billing cycle maps to its length in days`() {
        assertEquals(listOf(1L, 7L, 30L, 90L, 365L), BillingCycle.entries.map { SubscriptionDetectorService.cycleToDays(it) })
    }

    @Test
    fun `fewer than two values have no intervals and no spread`() {
        val one = Transaction(userId = userId, type = TransactionType.DEBIT, amount = BigDecimal.TEN, transactedAt = now)
        assertTrue(SubscriptionDetectorService.computeIntervals(listOf(one)).isEmpty())
        assertEquals(0.0, SubscriptionDetectorService.stddev(listOf(30.0)))
        assertEquals(1.0, SubscriptionDetectorService.stddev(listOf(29.0, 31.0)))
    }
}
