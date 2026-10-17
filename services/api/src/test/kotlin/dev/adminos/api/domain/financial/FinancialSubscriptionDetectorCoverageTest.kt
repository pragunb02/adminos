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
    private val detector = SubscriptionDetectorService(transactions, subscriptions,
        dev.adminos.api.domain.identity.UserCalendar(dev.adminos.api.domain.identity.InMemoryUserRepository(), Clock.fixed(now, ZoneOffset.UTC)))

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
        assertEquals(LocalDate.of(2026, 11, 11), sub.nextBillingDate, "a calendar month after the last charge (AOS-513), not +30 days")
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
        assertEquals(sub.lastBilledDate!!.plusMonths(3), sub.nextBillingDate)
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
    fun `charges older than 400 days, other users' charges and refunds are not considered`() = runBlocking<Unit> {
        charge("Netflix", "649.00", 430); charge("Netflix", "649.00", 65) // a year apart, but the first is outside the window
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

    // ── AOS-513 ──

    @Test
    fun `a yearly plan is found from two charges a year apart`() = runBlocking<Unit> {
        listOf(370L, 5L).forEach { charge("Amazon Prime", "1499.00", it) }
        val sub = detector.detectSubscriptions(userId).single()
        assertEquals(BillingCycle.YEARLY, sub.billingCycle)
        assertEquals(sub.lastBilledDate!!.plusYears(1), sub.nextBillingDate)
    }

    @Test
    fun `the category comes from the catalogue, or the charges, else other`() = runBlocking<Unit> {
        listOf(65L, 35L, 5L).forEach { charge("Netflix", "649.00", it) }
        assertEquals(SubscriptionCategory.ENTERTAINMENT, detector.detectSubscriptions(userId).single().category)
        assertEquals(SubscriptionCategory.CLOUD, SubscriptionDetectorService.fromCatalogue(TransactionCategory.SUBSCRIPTION, "cloud"))
        assertEquals(SubscriptionCategory.FITNESS, SubscriptionDetectorService.fromCatalogue(TransactionCategory.HEALTH, "fitness"))
        assertEquals(SubscriptionCategory.FOOD, SubscriptionDetectorService.fromCatalogue(TransactionCategory.FOOD, "food_delivery"))
        assertEquals(SubscriptionCategory.PRODUCTIVITY, SubscriptionDetectorService.fromCatalogue(TransactionCategory.SUBSCRIPTION, "apps"))
        assertEquals(SubscriptionCategory.NEWS, SubscriptionDetectorService.fromCatalogue(TransactionCategory.SUBSCRIPTION, "news"))
        assertEquals(SubscriptionCategory.GAMING, SubscriptionDetectorService.fromCatalogue(TransactionCategory.SUBSCRIPTION, "gaming"))
        assertEquals(SubscriptionCategory.ENTERTAINMENT, SubscriptionDetectorService.fromCatalogue(TransactionCategory.ENTERTAINMENT, null))
        assertEquals(null, SubscriptionDetectorService.fromCatalogue(TransactionCategory.SUBSCRIPTION, "books"))
        fun t(c: TransactionCategory) = Transaction(userId = userId, type = TransactionType.DEBIT, amount = BigDecimal.ONE, category = c, transactedAt = now)
        assertEquals(SubscriptionCategory.FOOD, detector.categoryFor("Unknown kitchen club", listOf(t(TransactionCategory.FOOD))))
        assertEquals(SubscriptionCategory.ENTERTAINMENT, detector.categoryFor("Some theatre", listOf(t(TransactionCategory.ENTERTAINMENT))))
        assertEquals(SubscriptionCategory.PRODUCTIVITY, detector.categoryFor("A course", listOf(t(TransactionCategory.EDUCATION))))
        assertEquals(SubscriptionCategory.OTHER, detector.categoryFor("Gym-ish", listOf(t(TransactionCategory.OTHER))))
    }

    @Test
    fun `a subscription with no charge for two cycles is marked ended, and a new charge brings it back`() = runBlocking<Unit> {
        val old = subscriptions.save(Subscription(userId = userId, name = "Old gym", merchantName = "old gym", amount = BigDecimal("999.00"),
            billingCycle = BillingCycle.MONTHLY, lastBilledDate = LocalDate.of(2026, 8, 1), nextBillingDate = LocalDate.of(2026, 9, 1)))
        val recent = subscriptions.save(Subscription(userId = userId, name = "Fresh", merchantName = "fresh", amount = BigDecimal("99.00"),
            billingCycle = BillingCycle.MONTHLY, lastBilledDate = LocalDate.of(2026, 9, 20)))
        detector.detectSubscriptions(userId)
        val ended = subscriptions.findById(old.id)!!
        assertEquals(SubscriptionStatus.CANCELLED, ended.status)
        assertNull(ended.nextBillingDate)
        assertEquals(mapOf("endedBy" to "detector", "endedReason" to "No charge since 2026-08-01"), ended.metadata)
        assertEquals(SubscriptionStatus.ACTIVE, subscriptions.findById(recent.id)!!.status, "two cycles not yet passed")

        listOf(65L, 35L, 5L).forEach { charge("Old gym", "999.00", it) }
        val back = subscriptions.findById(detector.detectSubscriptions(userId).single { it.merchantName == "old gym" }.id)!!
        assertEquals(SubscriptionStatus.ACTIVE, back.status)
        assertNull(back.metadata, "the ended reason is cleared")
    }

    @Test
    fun `dates are the user's own`() = runBlocking<Unit> {
        val users = dev.adminos.api.domain.identity.InMemoryUserRepository()
        val owner = users.save(dev.adminos.api.domain.identity.User(email = "p@example.com", googleId = "g", timezone = "Pacific/Kiritimati"))
        val d = SubscriptionDetectorService(transactions, subscriptions, dev.adminos.api.domain.identity.UserCalendar(users, Clock.fixed(now, ZoneOffset.UTC)))
        // 20:00Z is the next day in Kiritimati (UTC+14).
        listOf(Instant.parse("2026-08-10T20:00:00Z"), Instant.parse("2026-09-10T20:00:00Z"), Instant.parse("2026-10-10T20:00:00Z")).forEach {
            transactions.save(Transaction(userId = owner.id, type = TransactionType.DEBIT, amount = BigDecimal("199.00"), merchantName = "Hotstar", transactedAt = it))
        }
        val sub = d.detectSubscriptions(owner.id).single()
        assertEquals(LocalDate.of(2026, 10, 11), sub.lastBilledDate)
        assertEquals(LocalDate.of(2026, 11, 11), sub.nextBillingDate)
    }

    // ── AOS-516 ──

    @Test
    fun `a weekly grocery run, a monthly fuel top-up and a SIP are not subscriptions`() = runBlocking<Unit> {
        listOf(28L, 21L, 14L, 7L, 0L).forEach { charge("Zepto", "499.00", it) }
        listOf(90L, 60L, 30L, 0L).forEach { charge("Indian Oil", "2000.00", it) }
        listOf(90L, 60L, 30L, 0L).forEach { charge("Zerodha", "5000.00", it) }
        listOf(21L, 14L, 7L, 0L).zip(listOf("820.00", "1140.00", "760.00", "990.00")).forEach { (d, a) -> charge("Sharma General Store", a, d) }
        assertTrue(detector.detectSubscriptions(userId).isEmpty())
        assertTrue(subscriptions.findByUserId(userId).isEmpty())
    }

    @Test
    fun `everyday shopping can come from the habit lenses too`() = runBlocking<Unit> {
        listOf(28L, 21L, 14L, 7L, 0L).forEach { charge("Corner Mart", "450.00", it) }
        assertEquals(1, detector.detectSubscriptions(userId).size, "an unknown merchant at the same price")
        val withLens = SubscriptionDetectorService(transactions, InMemorySubscriptionRepository(), dev.adminos.api.domain.identity.UserCalendar(
            dev.adminos.api.domain.identity.InMemoryUserRepository(), Clock.fixed(now, ZoneOffset.UTC)), everyday = { it.merchantName == "Corner Mart" })
        assertTrue(withLens.detectSubscriptions(userId).isEmpty())
    }

    @Test
    fun `a monthly plan turned yearly moves the same subscription to yearly and is not ended`() = runBlocking<Unit> {
        val monthly = subscriptions.save(Subscription(userId = userId, name = "Google One", merchantName = "google one", amount = BigDecimal("130.00"),
            billingCycle = BillingCycle.MONTHLY, lastBilledDate = LocalDate.of(2026, 4, 9),
            metadata = mapOf("endedBy" to "detector", "endedReason" to "old", "note" to "kept")))
        charge("Google One", "130.00", 250); charge("Google One", "130.00", 220); charge("Google One", "130.00", 190)
        charge("Google One", "1300.00", 160)
        val sub = detector.detectSubscriptions(userId).single()
        assertEquals(monthly.id, sub.id, "the same subscription, not a new one")
        assertEquals(BillingCycle.YEARLY, sub.billingCycle)
        assertEquals(SubscriptionStatus.ACTIVE, sub.status)
        assertEquals(0, BigDecimal("1300.00").compareTo(sub.amount))
        assertEquals(sub.lastBilledDate!!.plusYears(1), sub.nextBillingDate)
        assertFalse(sub.priceChanged, "a plan switch is not a price rise")
        assertNull(sub.priceChangePct)
        assertEquals(mapOf("note" to "kept", "planSwitchedFrom" to "monthly", "planSwitchedOn" to sub.lastBilledDate.toString()), sub.metadata)
        assertEquals(1, subscriptions.countByUserId(userId))
        detector.detectSubscriptions(userId)
        assertEquals(SubscriptionStatus.ACTIVE, subscriptions.findById(monthly.id)!!.status, "months later it still isn't ended")
    }

    @Test
    fun `a yearly plan turned monthly is monthly, and a first detection after a switch is saved on the new cycle`() = runBlocking<Unit> {
        charge("Audible", "1990.00", 380); charge("Audible", "199.00", 45); charge("Audible", "199.00", 15)
        val sub = detector.detectSubscriptions(userId).single()
        assertEquals(BillingCycle.MONTHLY, sub.billingCycle)
        assertEquals(mapOf("planSwitchedFrom" to "yearly", "planSwitchedOn" to sub.firstBilledDate!!.plusDays(335).toString()), sub.metadata)
        assertFalse(sub.priceChanged)
    }

    @Test
    fun `a switch needs the opposite cycle before it and a steady cycle after it`() {
        fun t(daysAgo: Long, amount: String) = Transaction(userId = userId, type = TransactionType.DEBIT, amount = BigDecimal(amount),
            merchantName = "X", transactedAt = day(daysAgo))
        val weeklyThenBig = listOf(t(28, "100"), t(21, "100"), t(14, "100"), t(7, "1000"))
        val r = detector.readCycle(weeklyThenBig, ZoneOffset.UTC)
        assertEquals(BillingCycle.WEEKLY, r?.cycle, "weekly before a 10x charge isn't a monthly plan: read as a whole")
        assertNull(r?.switch)
        assertNull(detector.readCycle(listOf(t(400, "1990"), t(60, "199"), t(51, "199"), t(2, "199")), ZoneOffset.UTC), "irregular after the switch")
        val one = detector.readCycle(listOf(t(100, "1999"), t(70, "19990")), ZoneOffset.UTC)
        assertEquals(BillingCycle.YEARLY, one?.cycle)
        assertEquals(BillingCycle.MONTHLY, one?.previousCycle, "implied by the direction")
        assertFailsWith<IllegalArgumentException> { CycleReading(BillingCycle.MONTHLY, emptyList(), emptyList(), null, null) }
        assertFailsWith<IllegalArgumentException> { CycleReading(BillingCycle.MONTHLY, weeklyThenBig, emptyList(), null, BillingCycle.YEARLY) }
    }

    @Test
    fun `charges that vary before a switch are not a subscription`() = runBlocking<Unit> {
        charge("Iron Gym", "1500.00", 150); charge("Iron Gym", "900.00", 120); charge("Iron Gym", "1500.00", 90); charge("Iron Gym", "15000.00", 60)
        assertTrue(detector.detectSubscriptions(userId).isEmpty())
    }

    @Test
    fun `the lens view of everyday spending uses the quick-commerce and food-delivery lenses`() {
        val lenses = dev.adminos.api.domain.coach.lenses.LensMatcher()
        val everyday = dev.adminos.api.domain.coach.lenses.LensEverydaySpending(lenses)
        fun t(m: String) = Transaction(userId = userId, type = TransactionType.DEBIT, amount = BigDecimal.TEN, merchantName = m, transactedAt = now)
        assertTrue(everyday.isEveryday(t("Zepto")))
        assertTrue(everyday.isEveryday(t("Zomato")))
        assertFalse(everyday.isEveryday(t("Netflix")))
        assertFalse(everyday.isEveryday(t("Myntra")), "shopping is a habit lens, not everyday")
        assertFailsWith<IllegalArgumentException> { dev.adminos.api.domain.coach.lenses.LensEverydaySpending(lenses, emptySet()) }
        assertFailsWith<IllegalArgumentException> {
            dev.adminos.api.domain.coach.lenses.LensEverydaySpending(lenses, setOf(dev.adminos.api.domain.coach.lenses.LensId("gambling")))
        }
    }

    @Test
    fun `month ends stay month ends`() {
        assertEquals(LocalDate.of(2027, 2, 28), SubscriptionDetectorService.nextCharge(LocalDate.of(2027, 1, 31), BillingCycle.MONTHLY))
        assertEquals(LocalDate.of(2026, 10, 17), SubscriptionDetectorService.nextCharge(LocalDate.of(2026, 10, 16), BillingCycle.DAILY))
        assertEquals(LocalDate.of(2026, 10, 23), SubscriptionDetectorService.nextCharge(LocalDate.of(2026, 10, 16), BillingCycle.WEEKLY))
    }
}
