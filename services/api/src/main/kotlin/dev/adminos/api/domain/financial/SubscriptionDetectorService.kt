package dev.adminos.api.domain.financial

import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detects recurring subscriptions from transaction patterns.
 * Analyzes 90 days of debit transactions, groups by merchant,
 * computes intervals, and determines billing cycles.
 */
class SubscriptionDetectorService(
    private val transactionRepository: TransactionRepository,
    private val subscriptionRepository: SubscriptionRepository
) {
    private val logger = LoggerFactory.getLogger(SubscriptionDetectorService::class.java)

    /**
     * Run subscription detection for a user.
     * Analyzes last 90 days of debit transactions.
     */
    suspend fun detectSubscriptions(userId: UUID): List<Subscription> {
        val since = Instant.now().minus(90, ChronoUnit.DAYS)
        val debits = transactionRepository.findDebitsByUserIdSince(userId, since)

        // Group by normalized merchant name
        val groups = debits
            .filter { !it.merchantName.isNullOrBlank() }
            .groupBy { it.merchantName!!.lowercase().trim() }

        val detected = mutableListOf<Subscription>()

        for ((merchant, txns) in groups) {
            if (txns.size < 2) continue

            val sorted = txns.sortedBy { it.transactedAt }
            val intervals = computeIntervals(sorted)
            if (intervals.isEmpty()) continue

            val avgInterval = intervals.average()
            val stdDev = stddev(intervals)
            val cycle = determineBillingCycle(avgInterval, stdDev) ?: continue

            // Detect price changes
            val amounts = sorted.map { it.amount }
            val latest = amounts.last()
            val previous = amounts[amounts.size - 2]
            val priceChanged = previous > BigDecimal.ZERO &&
                abs(latest.subtract(previous).toDouble()) / previous.toDouble() > 0.05
            val priceChangePct = if (priceChanged && previous > BigDecimal.ZERO) {
                latest.subtract(previous)
                    .multiply(BigDecimal(100))
                    .divide(previous, 2, RoundingMode.HALF_UP)
            } else null

            val firstDate = sorted.first().transactedAt.atZone(ZoneOffset.UTC).toLocalDate()
            val lastDate = sorted.last().transactedAt.atZone(ZoneOffset.UTC).toLocalDate()
            val nextDate = lastDate.plusDays(cycleToDays(cycle))

            // Upsert subscription
            val existing = subscriptionRepository.findByUserAndMerchantAndCycle(userId, merchant, cycle)
            val subscription = if (existing != null) {
                subscriptionRepository.update(existing.copy(
                    amount = latest,
                    lastBilledDate = lastDate,
                    nextBillingDate = nextDate,
                    priceChanged = priceChanged,
                    priceChangePct = priceChangePct,
                    transactionIds = sorted.map { it.id },
                    status = SubscriptionStatus.ACTIVE
                ))
            } else {
                subscriptionRepository.save(Subscription(
                    userId = userId,
                    name = sorted.first().merchantName ?: merchant,
                    merchantName = merchant,
                    amount = latest,
                    billingCycle = cycle,
                    firstBilledDate = firstDate,
                    lastBilledDate = lastDate,
                    nextBillingDate = nextDate,
                    detectionSource = sorted.first().sourceType,
                    priceChanged = priceChanged,
                    priceChangePct = priceChangePct,
                    transactionIds = sorted.map { it.id }
                ))
            }

            detected.add(subscription)
            logger.info("Detected subscription: user={}, merchant={}, cycle={}", userId, merchant, cycle)
        }

        return detected
    }

    companion object {
        fun computeIntervals(sortedTxns: List<Transaction>): List<Double> {
            if (sortedTxns.size < 2) return emptyList()
            return sortedTxns.zipWithNext().map { (a, b) ->
                ChronoUnit.DAYS.between(
                    a.transactedAt.atZone(ZoneOffset.UTC).toLocalDate(),
                    b.transactedAt.atZone(ZoneOffset.UTC).toLocalDate()
                ).toDouble()
            }
        }

        fun determineBillingCycle(avgInterval: Double, stdDev: Double): BillingCycle? = when {
            avgInterval in 6.0..8.0 && stdDev < 2.0 -> BillingCycle.WEEKLY
            avgInterval in 25.0..35.0 && stdDev < 5.0 -> BillingCycle.MONTHLY
            avgInterval in 85.0..95.0 && stdDev < 10.0 -> BillingCycle.QUARTERLY
            avgInterval in 355.0..375.0 && stdDev < 15.0 -> BillingCycle.YEARLY
            else -> null
        }

        fun cycleToDays(cycle: BillingCycle): Long = when (cycle) {
            BillingCycle.DAILY -> 1
            BillingCycle.WEEKLY -> 7
            BillingCycle.MONTHLY -> 30
            BillingCycle.QUARTERLY -> 90
            BillingCycle.YEARLY -> 365
        }

        fun stddev(values: List<Double>): Double {
            if (values.size < 2) return 0.0
            val mean = values.average()
            val variance = values.map { (it - mean) * (it - mean) }.average()
            return sqrt(variance)
        }
    }
}
