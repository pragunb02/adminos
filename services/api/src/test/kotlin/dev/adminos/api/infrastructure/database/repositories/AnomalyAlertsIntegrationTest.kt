package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.agent.Anomaly
import dev.adminos.api.domain.agent.AnomalyAlerts
import dev.adminos.api.domain.agent.AnomalyDetectorService
import dev.adminos.api.domain.agent.AnomalyType
import dev.adminos.api.domain.common.DuplicateEntityException
import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.events.IngestEvent
import dev.adminos.api.domain.ingestion.events.IngestSource
import dev.adminos.api.domain.ingestion.pipeline.*
import dev.adminos.api.domain.notifications.NotificationService
import dev.adminos.api.domain.notifications.NotificationType
import dev.adminos.api.domain.notifications.delivery.InAppChannel
import dev.adminos.api.domain.notifications.delivery.NotificationDispatcher
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import dev.adminos.api.infrastructure.database.dbQuery
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** AOS-505 on Postgres: an SMS for a ₹1 test charge becomes a transaction, an anomaly and one alert. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnomalyAlertsIntegrationTest {
    private val events = ExposedIngestEventRepository()
    private val txns = ExposedTransactionRepository()
    private val anomalies = ExposedAnomalyRepository()
    private val notifications = ExposedNotificationRepository()
    private val users = ExposedUserRepository()
    private val alerts = AnomalyAlerts(
        AnomalyDetectorService(anomalies, txns, users),
        NotificationService(ExposedNotificationPreferencesRepository(), notifications, NotificationDispatcher(listOf(InAppChannel()), users, notifications))
    )
    private lateinit var user: UUID

    private val processor by lazy {
        IngestProcessor(
            events,
            IngestPipeline(listOf(ToTransactionStep(), NormalizeMerchantStep(MerchantNormalizer()), CategorizeStep(CategorizationService(ExposedCategoryRuleRepository())))),
            RepositoryOutcomeWriter(txns, events, ExposedTransactionLinkRepository()) { w -> dbQuery { w() } },
            // The shared database holds other tests' events too: only react to this test's user.
            listeners = listOf(TransactionCreatedListener { if (it.userId == user) alerts.onCreated(it) })
        )
    }

    @BeforeAll
    fun start() {
        PostgresTestDatabase.ensureStarted()
        user = runBlocking { PostgresTestDatabase.newUser() }
    }

    @Test
    fun `a 1-rupee card test becomes an anomaly and one in-app alert, and re-checking adds nothing`() = runBlocking<Unit> {
        val event = IngestEvent(userId = user, source = IngestSource.SMS, sourceRef = UUID.randomUUID().toString(),
            occurredAt = Instant.now().minusSeconds(600), direction = IngestDirection.DEBIT, amount = BigDecimal("1.00"),
            merchantRaw = "STRIPE*VERIFY", accountHint = "4521", paymentMethod = PaymentMethod.CARD_CREDIT)
        events.saveNew(listOf(event))
        while (processor.processBatch() > 0) {}

        val t = assertNotNull(txns.findByIngestEventId(event.id))
        val anomaly = assertNotNull(anomalies.findByTransactionId(t.id))
        assertEquals(AnomalyType.CARD_TESTING, anomaly.type)
        assertEquals(1, notifications.findByUserAndType(user, NotificationType.ANOMALY).size)

        alerts.onCreated(t) // e.g. the event was re-claimed after a crash
        assertEquals(1L, anomalies.countByUserId(user))
        assertEquals(1, notifications.findByUserAndType(user, NotificationType.ANOMALY).size)

        // V20: the database itself refuses a second anomaly for the transaction.
        assertFailsWith<DuplicateEntityException> {
            anomalies.save(Anomaly(userId = user, transactionId = t.id, type = AnomalyType.LARGE_AMOUNT, confidenceScore = 0.7, reason = "x"))
        }
    }
}
