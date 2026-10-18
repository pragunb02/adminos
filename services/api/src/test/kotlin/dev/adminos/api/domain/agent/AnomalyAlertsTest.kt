package dev.adminos.api.domain.agent

import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.notifications.InMemoryNotificationPreferencesRepository
import dev.adminos.api.domain.notifications.InMemoryNotificationRepository
import dev.adminos.api.domain.notifications.NotificationType
import dev.adminos.api.domain.notifications.notificationService
import dev.adminos.api.domain.scheduling.TestClock
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** When an anomaly becomes an alert (AOS-505). */
class AnomalyAlertsTest {
    private val clock = TestClock(Instant.parse("2026-10-16T09:30:00Z"))
    private val userId = UUID.randomUUID()
    private val anomalies = InMemoryAnomalyRepository()
    private val transactions = InMemoryTransactionRepository()
    private val notifications = InMemoryNotificationRepository()
    private val alerts = AnomalyAlerts(
        AnomalyDetectorService(anomalies, transactions, dev.adminos.api.domain.identity.UserCalendar(InMemoryUserRepository()), InMemoryAnomalyToleranceRepository()),
        notificationService(InMemoryNotificationPreferencesRepository(), notifications, clock = clock),
        clock
    )

    private suspend fun spend(amount: String, at: Instant = clock.now.minus(Duration.ofHours(1)), currency: String = "INR", merchant: String? = "STRIPE*TEST") =
        transactions.save(Transaction(userId = userId, type = TransactionType.DEBIT, amount = BigDecimal(amount), currency = currency,
            merchantName = merchant, transactedAt = at))

    private suspend fun sent() = notifications.findByUserAndType(userId, NotificationType.ANOMALY)

    @Test
    fun `a confident, recent anomaly alerts the user with what happened and a link to it`() = runBlocking<Unit> {
        val t = spend("1.00")
        alerts.onCreated(t)
        val anomaly = assertNotNull(anomalies.findByTransactionId(t.id))
        val alert = sent().single()
        assertEquals("Unusual charge: ₹1.00 at STRIPE*TEST", alert.title)
        assertEquals("Amount of exactly ₹1.00 matches card testing pattern", alert.body)
        assertEquals(mapOf("entity_type" to "anomaly", "entity_id" to anomaly.id.toString(), "action" to "review_anomaly"), alert.data)
    }

    @Test
    fun `a charge without a merchant still reads well`() = runBlocking<Unit> {
        alerts.onCreated(spend("2.00", merchant = null))
        assertEquals("Unusual charge: ₹2.00", sent().single().title)
    }

    @Test
    fun `a low-confidence anomaly is recorded but not pushed`() = runBlocking<Unit> {
        // 02:00 IST food order: odd hours alone is 0.6
        val t = spend("349.00", at = Instant.parse("2026-10-15T20:30:00Z"), merchant = "Swiggy")
        alerts.onCreated(t)
        assertEquals(AnomalyType.ODD_HOURS, anomalies.findByTransactionId(t.id)?.type)
        assertTrue(sent().isEmpty())
    }

    @Test
    fun `an old transaction (an imported statement) is recorded but not pushed`() = runBlocking<Unit> {
        val t = spend("1.00", at = clock.now.minus(Duration.ofDays(4)))
        alerts.onCreated(t)
        assertNotNull(anomalies.findByTransactionId(t.id))
        assertTrue(sent().isEmpty())
    }

    @Test
    fun `nothing unusual means nothing recorded and nothing sent, and a second look sends nothing more`() = runBlocking<Unit> {
        alerts.onCreated(spend("349.00", merchant = "Swiggy"))
        assertEquals(0L, anomalies.countByUserId(userId))
        val t = spend("1.00")
        alerts.onCreated(t); alerts.onCreated(t)
        assertEquals(1, sent().size)
    }
}
