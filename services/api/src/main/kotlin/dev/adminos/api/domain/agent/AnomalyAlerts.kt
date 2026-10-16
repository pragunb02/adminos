package dev.adminos.api.domain.agent

import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.ingestion.pipeline.TransactionCreatedListener
import dev.adminos.api.domain.notifications.NotificationService
import dev.adminos.api.domain.notifications.NotificationType
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/**
 * Unusual-charge alerts (AOS-505): every new transaction is checked; an anomaly is always recorded
 * (it shows under Anomalies), but the user is only *notified* when it is worth it:
 * - confident enough (≥ [MIN_ALERT_CONFIDENCE]: a lone 1 a.m. food order is recorded, not pushed);
 * - recent (within [ALERT_WINDOW]): importing last year's statement mustn't send a hundred alerts.
 */
class AnomalyAlerts(
    private val detector: AnomalyDetectorService,
    private val notifications: NotificationService,
    private val clock: Clock = Clock.systemUTC()
) : TransactionCreatedListener {
    private val logger = LoggerFactory.getLogger(AnomalyAlerts::class.java)

    override suspend fun onCreated(transaction: Transaction) {
        val anomaly = detector.evaluate(transaction) ?: return
        when {
            anomaly.confidenceScore < MIN_ALERT_CONFIDENCE ->
                logger.info("Anomaly {} recorded without an alert: confidence {}", anomaly.id, anomaly.confidenceScore)
            transaction.transactedAt.isBefore(clock.instant().minus(ALERT_WINDOW)) ->
                logger.info("Anomaly {} recorded without an alert: the transaction is older than {}", anomaly.id, ALERT_WINDOW)
            else -> notifications.send(
                userId = transaction.userId,
                type = NotificationType.ANOMALY,
                title = "Unusual charge: ₹${transaction.amount.toPlainString()}${transaction.merchantName?.let { " at $it" } ?: ""}",
                body = anomaly.reason,
                data = mapOf("entity_type" to "anomaly", "entity_id" to anomaly.id.toString(), "action" to "review_anomaly")
            )
        }
    }

    companion object {
        const val MIN_ALERT_CONFIDENCE = 0.7
        val ALERT_WINDOW: Duration = Duration.ofDays(3)
    }
}
