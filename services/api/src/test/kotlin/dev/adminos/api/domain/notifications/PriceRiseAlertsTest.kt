package dev.adminos.api.domain.notifications

import dev.adminos.api.domain.financial.BillingCycle
import dev.adminos.api.domain.financial.InMemoryPriceRiseRepository
import dev.adminos.api.domain.financial.InMemorySubscriptionRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.PriceRise
import dev.adminos.api.domain.financial.Subscription
import dev.adminos.api.domain.financial.SubscriptionDetectorService
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.financial.WasteContext
import dev.adminos.api.domain.financial.WasteFactor
import dev.adminos.api.domain.financial.WasteScorer
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.UserCalendar
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-715: detection records a price rise once per new price, and the user is told once. */
class PriceRiseAlertsTest {
    private val now = Instant.parse("2026-10-16T06:30:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val userId = UUID.randomUUID()
    private val transactions = InMemoryTransactionRepository()
    private val subscriptions = InMemorySubscriptionRepository()
    private val rises = InMemoryPriceRiseRepository()
    private val prefs = InMemoryNotificationPreferencesRepository()
    private val notificationRepo = InMemoryNotificationRepository()
    private val calendar = UserCalendar(InMemoryUserRepository(), clock)
    private val detector = SubscriptionDetectorService(transactions, subscriptions, calendar, rises)
    private val alerts = PriceRiseAlerts(rises, subscriptions, notificationService(prefs, notificationRepo, clock = clock), calendar, clock)

    private suspend fun charge(amount: String, daysAgo: Long, merchant: String = "Netflix") = transactions.save(Transaction(
        userId = userId, type = TransactionType.DEBIT, amount = BigDecimal(amount), merchantName = merchant,
        paymentMethod = PaymentMethod.CARD_CREDIT, transactedAt = now.minusSeconds(daysAgo * 86_400)))

    private suspend fun sent() = notificationRepo.findByUserAndType(userId, NotificationType.SUBSCRIPTION_FLAG)

    @Test
    fun `a rise is recorded and told once, with the old and new amount`() = runBlocking<Unit> {
        listOf(95L, 65L, 35L).forEach { charge("649.00", it) }
        val last = charge("799.00", 5)
        val sub = detector.detectSubscriptions(userId).single()
        assertTrue(sub.priceChanged)
        assertEquals(BigDecimal("23.11"), sub.priceChangePct)
        assertEquals(last.id, rises.findUnnotified(userId).single().transactionId)

        assertEquals(PriceRiseRun(1, 0), alerts.notifyPending(userId))
        val n = sent().single()
        assertEquals("Netflix went up: ₹649 → ₹799 (+23%)", n.title)
        assertEquals("Charged ₹799 on 11 Oct 2026; it was usually ₹649. If it isn't worth the new price, Subscriptions shows how to cancel.", n.body)
        assertEquals(mapOf("entity_type" to "subscription", "entity_id" to sub.id.toString(), "action" to "view_subscription"), n.data)

        // The same night again, and the next night: nothing more.
        detector.detectSubscriptions(userId)
        assertEquals(PriceRiseRun(0, 0), alerts.notifyPending(userId))
        assertEquals(1, sent().size)
    }

    @Test
    fun `the same new price next month is not a new rise, a higher one is`() = runBlocking<Unit> {
        listOf(155L, 125L, 95L).forEach { charge("649.00", it) }
        charge("799.00", 65); detector.detectSubscriptions(userId); alerts.notifyPending(userId)
        charge("799.00", 35); detector.detectSubscriptions(userId)
        assertEquals(PriceRiseRun(0, 0), alerts.notifyPending(userId), "₹799 again: already told")
        charge("899.00", 5)
        detector.detectSubscriptions(userId)
        assertEquals(PriceRiseRun(1, 0), alerts.notifyPending(userId))
        assertEquals(listOf("Netflix went up: ₹649 → ₹799 (+23%)", "Netflix went up: ₹799 → ₹899 (+13%)"), sent().map { it.title }.sorted())
    }

    @Test
    fun `a price cut or a yearly plan switch is never an alert`() = runBlocking<Unit> {
        listOf(95L, 65L, 35L).forEach { charge("649.00", it) }
        charge("499.00", 5)
        val cut = detector.detectSubscriptions(userId).single()
        assertTrue(cut.priceChanged)
        assertEquals(BigDecimal("-23.11"), cut.priceChangePct)
        assertEquals(PriceRiseRun(0, 0), alerts.notifyPending(userId))
    }

    @Test
    fun `the waste score's price-rise factor follows the same rule`() = runBlocking<Unit> {
        listOf(125L, 95L, 65L).forEach { charge("199.00", it) }
        charge("215.00", 35) // +₹16: under ₹20, not a rise
        val small = detector.detectSubscriptions(userId).single()
        assertFalse(small.priceChanged)
        val context = WasteContext(LocalDate.of(2026, 10, 16), listOf(small), null, emptyList())
        assertFalse(WasteFactor.PRICE_INCREASED in WasteScorer.score(small, context).factors)
        charge("249.00", 5) // +₹50 on the median ₹199
        val risen = detector.detectSubscriptions(userId).single()
        assertTrue(WasteFactor.PRICE_INCREASED in WasteScorer.score(risen, context.copy(subscriptions = listOf(risen))).factors)
    }

    @Test
    fun `a user who switched subscription alerts off isn't told, and isn't told later either`() = runBlocking<Unit> {
        prefs.save(NotificationPreferences.defaultFor(userId).copy(subscriptionFlags = false))
        listOf(95L, 65L, 35L).forEach { charge("649.00", it) }
        charge("799.00", 5)
        detector.detectSubscriptions(userId)
        assertEquals(PriceRiseRun(0, 0), alerts.notifyPending(userId))
        assertEquals(0, sent().size)
        assertEquals(emptyList(), rises.findUnnotified(userId))
    }

    @Test
    fun `a failed send is tried again on the next run`() = runBlocking<Unit> {
        listOf(95L, 65L, 35L).forEach { charge("649.00", it) }
        charge("799.00", 5)
        detector.detectSubscriptions(userId)
        val failing = PriceRiseAlerts(rises, object : dev.adminos.api.domain.financial.SubscriptionRepository by subscriptions {
            override suspend fun findById(id: UUID): Subscription? = throw java.io.IOException("db timeout")
        }, notificationService(prefs, notificationRepo, clock = clock), calendar, clock)
        assertEquals(PriceRiseRun(0, 1), failing.notifyPending(userId))
        assertEquals(1, rises.findUnnotified(userId).size)
        assertEquals(PriceRiseRun(1, 0), alerts.notifyPending(userId))
    }

    @Test
    fun `a rise whose subscription is gone, or isn't the user's, is closed without an alert`() = runBlocking<Unit> {
        val gone = PriceRise(userId = userId, subscriptionId = UUID.randomUUID(), oldAmount = BigDecimal("100.00"), newAmount = BigDecimal("150.00"),
            currency = "INR", chargedOn = LocalDate.of(2026, 10, 1), transactionId = UUID.randomUUID())
        val someoneElses = subscriptions.save(Subscription(userId = UUID.randomUUID(), name = "Spotify", amount = BigDecimal("119.00"), billingCycle = BillingCycle.MONTHLY))
        rises.recordIfNew(gone)
        rises.recordIfNew(gone.copy(id = UUID.randomUUID(), subscriptionId = someoneElses.id))
        assertEquals(PriceRiseRun(0, 0), alerts.notifyPending(userId))
        assertEquals(emptyList(), rises.findUnnotified(userId))
        assertEquals(0, sent().size)
    }

    @Test
    fun `the text names the subscription, rounds the percent and shows other currencies plainly`() {
        val rise = PriceRise(userId = userId, subscriptionId = UUID.randomUUID(), oldAmount = BigDecimal("20.00"), newAmount = BigDecimal("24.99"),
            currency = "USD", chargedOn = LocalDate.of(2026, 3, 4), transactionId = UUID.randomUUID())
        val unnamed = Subscription(userId = userId, name = " ", merchantName = "openai", amount = BigDecimal("24.99"), billingCycle = BillingCycle.MONTHLY)
        assertEquals("openai went up: USD 20.00 → USD 24.99 (+25%)", PriceRiseAlerts.title(unnamed, rise))
        assertEquals("A subscription went up: USD 20.00 → USD 24.99 (+25%)", PriceRiseAlerts.title(unnamed.copy(merchantName = null), rise))
        assertTrue(PriceRiseAlerts.body(rise).startsWith("Charged USD 24.99 on 4 Mar 2026; it was usually USD 20.00."))
        assertEquals(25, PriceRiseAlerts.percent(rise))
    }

    @Test
    fun `a hostile subscription name can't fake lines, reverse text or flood the notice`() {
        val rise = PriceRise(userId = userId, subscriptionId = UUID.randomUUID(), oldAmount = BigDecimal("100.00"), newAmount = BigDecimal("150.00"),
            currency = "INR", chargedOn = LocalDate.of(2026, 3, 4), transactionId = UUID.randomUUID())
        val nasty = Subscription(userId = userId, name = "Netflix\nYour account is locked: call 9800000000\u202E<img src=x onerror=alert(1)>",
            amount = BigDecimal("150.00"), billingCycle = BillingCycle.MONTHLY)
        val title = PriceRiseAlerts.title(nasty, rise)
        assertFalse(title.contains('\n') || title.contains('\u202E'), title)
        assertTrue(title.startsWith("Netflix Your account is locked"), "markup and words stay literal text: $title")
        assertTrue(PriceRiseAlerts.displayName("x".repeat(500)).length == PriceRiseAlerts.MAX_NAME)
        assertEquals("A subscription went up: ₹100 → ₹150 (+50%)", PriceRiseAlerts.title(nasty.copy(name = "\u200B\u200B", merchantName = "\n"), rise))
    }

    @Test
    fun `nothing pending sends nothing`() = runBlocking<Unit> {
        assertEquals(PriceRiseRun(0, 0), alerts.notifyPending(userId))
        assertNull(rises.findUnnotified(userId).firstOrNull())
    }

    @Test
    fun `a monthly subscription that switched to a yearly plan isn't marked as a price change`() = runBlocking<Unit> {
        listOf(125L, 95L, 65L, 35L).forEach { charge("199.00", it, merchant = "Spotify") }
        charge("1999.00", 5, merchant = "Spotify")
        val sub = assertNotNull(detector.detectSubscriptions(userId).singleOrNull())
        assertFalse(sub.priceChanged)
        assertEquals(PriceRiseRun(0, 0), alerts.notifyPending(userId))
    }
}
