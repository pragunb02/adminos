package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.BillingCycle
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.PriceRise
import dev.adminos.api.domain.financial.Subscription
import dev.adminos.api.domain.financial.SubscriptionDetectorService
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.domain.notifications.NotificationService
import dev.adminos.api.domain.notifications.NotificationType
import dev.adminos.api.domain.notifications.PriceRiseAlerts
import dev.adminos.api.domain.notifications.delivery.InAppChannel
import dev.adminos.api.domain.notifications.delivery.NotificationDispatcher
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-715 on Postgres: V34 records a rise once per (subscription, price), and the job tells the user once. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PriceRiseIntegrationTest {
    private val rises = ExposedPriceRiseRepository()
    private val subscriptions = ExposedSubscriptionRepository()
    private val transactions = ExposedTransactionRepository()
    private val now = Instant.now().truncatedTo(ChronoUnit.MICROS)

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private suspend fun subscription(user: UUID) = subscriptions.save(Subscription(userId = user, name = "Netflix", merchantName = "netflix",
        amount = BigDecimal("799.00"), billingCycle = BillingCycle.MONTHLY))

    private fun rise(user: UUID, sub: UUID, amount: String, createdAt: Instant = now) = PriceRise(userId = user, subscriptionId = sub,
        oldAmount = BigDecimal("649.00"), newAmount = BigDecimal(amount), currency = "INR", chargedOn = LocalDate.of(2026, 10, 11),
        transactionId = UUID.randomUUID(), createdAt = createdAt)

    @Test
    fun `a rise round-trips, and the same price in rupees is refused`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val sub = subscription(user).id
        val first = rise(user, sub, "799.00")
        assertTrue(rises.recordIfNew(first))
        assertFalse(rises.recordIfNew(rise(user, sub, "799.40")))
        assertTrue(rises.recordIfNew(rise(user, sub, "899.00", createdAt = now.plusSeconds(1))))
        val pending = rises.findUnnotified(user)
        assertEquals(listOf(BigDecimal("799.00"), BigDecimal("899.00")), pending.map { it.newAmount })
        assertEquals(first.copy(notifiedAt = null), pending.first())
        rises.markNotified(first.id, now)
        assertEquals(listOf(BigDecimal("899.00")), rises.findUnnotified(user).map { it.newAmount })
    }

    @Test
    fun `two runs recording the same rise at once store it once`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val sub = subscription(user).id
        val stored = (1..5).map { async(Dispatchers.IO) { rises.recordIfNew(rise(user, sub, "799.00")) } }.awaitAll()
        assertEquals(1, stored.count { it })
        assertEquals(1, rises.findUnnotified(user).size)
    }

    @Test
    fun `detection then alerts on Postgres tell the user once`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        listOf(95L to "649.00", 65L to "649.00", 35L to "649.00", 5L to "799.00").forEach { (days, amount) ->
            transactions.save(Transaction(userId = user, type = TransactionType.DEBIT, amount = BigDecimal(amount), merchantName = "Netflix",
                paymentMethod = PaymentMethod.CARD_CREDIT, transactedAt = now.minus(days, ChronoUnit.DAYS)))
        }
        val users = ExposedUserRepository()
        val calendar = UserCalendar(users, Clock.fixed(now, ZoneOffset.UTC))
        val notifications = ExposedNotificationRepository()
        val alerts = PriceRiseAlerts(rises, subscriptions,
            NotificationService(ExposedNotificationPreferencesRepository(), notifications, NotificationDispatcher(listOf(InAppChannel()), users, notifications)), calendar)
        val detector = SubscriptionDetectorService(transactions, subscriptions, calendar, rises)
        repeat(2) {
            detector.detectSubscriptions(user)
            alerts.notifyPending(user)
        }
        val sent = notifications.findByUserAndType(user, NotificationType.SUBSCRIPTION_FLAG)
        assertEquals(listOf("Netflix went up: ₹649 → ₹799 (+23%)"), sent.map { it.title })
    }
}
