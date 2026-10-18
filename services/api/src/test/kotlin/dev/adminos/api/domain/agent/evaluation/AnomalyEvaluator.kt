package dev.adminos.api.domain.agent.evaluation

import dev.adminos.api.domain.agent.AnomalyAlerts
import dev.adminos.api.domain.agent.AnomalyDetectorService
import dev.adminos.api.domain.agent.AnomalyReviewService
import dev.adminos.api.domain.agent.AnomalyStatus
import dev.adminos.api.domain.agent.DuplicateChargeRule
import dev.adminos.api.domain.agent.InMemoryAnomalyRepository
import dev.adminos.api.domain.agent.InMemoryAnomalyToleranceRepository
import dev.adminos.api.domain.agent.RobustAmountRule
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.InMemoryAuditRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.UserCalendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.util.UUID
import kotlin.math.roundToLong

/** A detector configuration to measure: which amount rule, and whether the simulated user answers alerts. */
data class DetectorVariant(
    val name: String,
    val amountRule: RobustAmountRule,
    val userAnswers: Boolean,
    /** Duplicates below this are recorded, not pushed; zero is the AOS-505 behaviour. */
    val smallDuplicate: BigDecimal = DuplicateChargeRule.SMALL_AMOUNT
)

/** What happened to one row: alerted (confidence ≥ 0.7, what notifies), and which rules fired. */
data class RowOutcome(val row: StreamRow, val alerted: Boolean, val rules: Set<String>)

@Serializable
data class LabelRecall(val label: String, val injected: Int, val alerted: Int)

@Serializable
data class AnomalyEvalReport(
    val variant: String,
    val rows: Int,
    val injected: Int,
    val alerts: Int,
    val truePositives: Int,
    val precision: Double,
    val recall: Double,
    /** False alerts per stream-month over the whole set (the research's target is at most 2). */
    val falseAlertsPerMonth: Double,
    /** The same, on the two streams with nothing injected. */
    val quietStreamAlertsPerMonth: Double,
    /** Alerts the amount rule took part in, and how many of those were injected anomalies. */
    val amountAlerts: Int,
    val amountTruePositives: Int,
    val perLabel: List<LabelRecall>,
    /** False alerts by what kind of normal they hit (festival, dining, yearly…) and which rule. */
    val falseAlertsByTag: Map<String, Int>,
    val falseAlertsByRule: Map<String, Int>
)

/**
 * Replays each synthetic stream through the real [AnomalyDetectorService] (AOS-713), one spend at a
 * time in time order, as ingestion would: save, then check. With [DetectorVariant.userAnswers] the
 * simulated user answers every alert truthfully (normal → "this is normal", injected → "unexpected"),
 * so the tolerance learning is measured too.
 */
object AnomalyEvaluator {
    val OLD = DetectorVariant("old (AOS-505): 3x 30-day category average", RobustAmountRule(RobustAmountRule.LEGACY_TIERS), userAnswers = false, smallDuplicate = BigDecimal.ZERO)
    val NEW_AMOUNT_ONLY = DetectorVariant("new amount rule, duplicates as before", RobustAmountRule(), userAnswers = false, smallDuplicate = BigDecimal.ZERO)
    val NEW = DetectorVariant("new (AOS-713): robust baselines + small duplicates not pushed", RobustAmountRule(), userAnswers = false)
    val NEW_WITH_ANSWERS = DetectorVariant("new + the user answers every alert (tolerance)", RobustAmountRule(), userAnswers = true)
    val VARIANTS = listOf(OLD, NEW_AMOUNT_ONLY, NEW, NEW_WITH_ANSWERS)

    suspend fun run(rows: List<StreamRow>, variant: DetectorVariant): List<RowOutcome> =
        rows.groupBy { it.stream }.toSortedMap().values.flatMap { replay(it.sortedWith(compareBy<StreamRow> { r -> r.at }.thenBy { r -> r.id }), variant) }

    private suspend fun replay(stream: List<StreamRow>, variant: DetectorVariant): List<RowOutcome> {
        val user = UUID.nameUUIDFromBytes(stream.first().stream.toByteArray())
        val transactions = InMemoryTransactionRepository()
        val anomalies = InMemoryAnomalyRepository()
        val tolerances = InMemoryAnomalyToleranceRepository()
        val detector = AnomalyDetectorService(anomalies, transactions, UserCalendar(InMemoryUserRepository()), tolerances, variant.amountRule, variant.smallDuplicate)
        val review = AnomalyReviewService(anomalies, transactions, tolerances, AuditService(InMemoryAuditRepository(), CoroutineScope(Dispatchers.Unconfined)))
        return stream.map { row ->
            val t = transactions.save(Transaction(
                id = UUID.nameUUIDFromBytes(row.id.toByteArray()), userId = user, type = TransactionType.DEBIT, nature = TransactionNature.SPEND,
                amount = row.amount, currency = row.currency, merchantName = row.merchant, category = row.category, transactedAt = row.at
            ))
            val anomaly = detector.evaluate(t)
            val alerted = anomaly != null && anomaly.confidenceScore >= AnomalyAlerts.MIN_ALERT_CONFIDENCE
            if (alerted && variant.userAnswers && anomaly != null) {
                review.resolve(user, anomaly.id, if (row.label.injected) AnomalyStatus.CONFIRMED_FRAUD else AnomalyStatus.CONFIRMED_SAFE)
            }
            RowOutcome(row, alerted, anomaly?.rulesFired.orEmpty())
        }
    }

    fun score(variant: DetectorVariant, outcomes: List<RowOutcome>): AnomalyEvalReport {
        val alerts = outcomes.filter { it.alerted }
        val truePositives = alerts.count { it.row.label.injected }
        val injected = outcomes.count { it.row.label.injected }
        val falseAlerts = alerts.filter { !it.row.label.injected }
        val streams = outcomes.map { it.row.stream }.distinct()
        val quiet = streams.filter { s -> outcomes.none { it.row.stream == s && it.row.label.injected } }
        val amount = alerts.filter { RobustAmountRule.NAME in it.rules }
        return AnomalyEvalReport(
            variant = variant.name,
            rows = outcomes.size,
            injected = injected,
            alerts = alerts.size,
            truePositives = truePositives,
            precision = ratio(truePositives, alerts.size),
            recall = ratio(truePositives, injected),
            falseAlertsPerMonth = round(falseAlerts.size.toDouble() / (streams.size * SpendingStreams.MONTHS)),
            quietStreamAlertsPerMonth = if (quiet.isEmpty()) 0.0 else round(alerts.count { it.row.stream in quiet }.toDouble() / (quiet.size * SpendingStreams.MONTHS)),
            amountAlerts = amount.size,
            amountTruePositives = amount.count { it.row.label.injected },
            perLabel = SpendLabel.entries.filter { it.injected }.map { l ->
                LabelRecall(l.code, outcomes.count { it.row.label == l }, alerts.count { it.row.label == l })
            },
            falseAlertsByTag = falseAlerts.groupingBy { it.row.tag }.eachCount().toSortedMap(),
            falseAlertsByRule = falseAlerts.flatMap { it.rules }.groupingBy { it }.eachCount().toSortedMap()
        )
    }

    private fun ratio(n: Int, d: Int): Double = if (d == 0) 0.0 else round(n.toDouble() / d)
    private fun round(x: Double): Double = (x * 10_000).roundToLong() / 10_000.0
}
