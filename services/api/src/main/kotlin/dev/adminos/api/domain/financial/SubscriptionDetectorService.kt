package dev.adminos.api.domain.financial

import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.sqrt

/**
 * Detects recurring subscriptions from transaction patterns (AOS-513 rework of the legacy detector).
 *
 * - **Window:** the last [LOOKBACK_DAYS] days, so a yearly plan (two charges ~365 days apart) can be
 *   found; it used to look back 90 days, which made yearly impossible and quarterly a coin toss.
 * - **Dates** are the user's own ([UserCalendar]), not UTC.
 * - **Next charge** follows the calendar (31 Jan → 28 Feb → 31 Mar), not "+30 days".
 * - **Category** comes from the merchant catalogue (streaming → Entertainment, cloud → Cloud…), not
 *   always Other.
 * - **Ended:** an active subscription with no charge for two cycles (+ a week) is marked cancelled,
 *   with the reason; a new charge brings it back.
 */
class SubscriptionDetectorService(
    private val transactionRepository: TransactionRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val calendar: dev.adminos.api.domain.identity.UserCalendar,
    private val catalogue: MerchantCatalogue = MerchantCatalogue.DEFAULT
) {
    private val logger = LoggerFactory.getLogger(SubscriptionDetectorService::class.java)

    /** Detects and updates the user's subscriptions; also ends ones whose charges stopped. @return those seen charging */
    suspend fun detectSubscriptions(userId: UUID): List<Subscription> {
        val zone = calendar.zoneOf(userId)
        val today = calendar.today(userId)
        val since = today.minusDays(LOOKBACK_DAYS).atStartOfDay(zone).toInstant()
        // A friend's bill share from Splitwise isn't a charge from a merchant (AOS-911).
        val debits = transactionRepository.findDebitsByUserIdSince(userId, since)
            .filter { it.sourceType != dev.adminos.api.domain.financial.splits.SplitMatcher.SPLITWISE_SOURCE }

        val groups = debits.mapNotNull { t -> t.merchantName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { it to t } }
            .groupBy({ it.first }, { it.second })

        val detected = mutableListOf<Subscription>()
        for ((merchant, txns) in groups) {
            if (txns.size < 2) continue
            val sorted = txns.sortedBy { it.transactedAt }
            val intervals = computeIntervals(sorted, zone)
            if (intervals.isEmpty()) continue
            val cycle = determineBillingCycle(intervals.average(), stddev(intervals)) ?: continue

            val amounts = sorted.map { it.amount }
            val latest = amounts.last()
            val previous = amounts[amounts.size - 2]
            val priceChanged = previous > BigDecimal.ZERO && (latest - previous).abs().divide(previous, 6, RoundingMode.HALF_UP) > BigDecimal("0.05")
            val priceChangePct = if (priceChanged) (latest - previous).multiply(BigDecimal(100)).divide(previous, 2, RoundingMode.HALF_UP) else null

            val firstDate = sorted.first().transactedAt.atZone(zone).toLocalDate()
            val lastDate = sorted.last().transactedAt.atZone(zone).toLocalDate()
            val nextDate = nextCharge(lastDate, cycle)
            val displayName = sorted.last().merchantName?.trim()?.takeIf { it.isNotEmpty() } ?: merchant
            val category = categoryFor(displayName, sorted)

            val existing = subscriptionRepository.findByUserAndMerchantAndCycle(userId, merchant, cycle)
            val subscription = if (existing != null) {
                subscriptionRepository.update(existing.copy(
                    amount = latest, lastBilledDate = lastDate, nextBillingDate = nextDate,
                    priceChanged = priceChanged, priceChangePct = priceChangePct, transactionIds = sorted.map { it.id },
                    // A new charge after it looked ended (or a user cancel) means it's charging again.
                    status = if (existing.status == SubscriptionStatus.TRIAL) SubscriptionStatus.TRIAL else SubscriptionStatus.ACTIVE,
                    category = if (existing.category == SubscriptionCategory.OTHER) category else existing.category,
                    metadata = existing.metadata?.minus(ENDED_KEYS)?.takeIf { it.isNotEmpty() }
                ))
            } else {
                subscriptionRepository.save(Subscription(
                    userId = userId, name = displayName, merchantName = merchant, category = category, amount = latest,
                    billingCycle = cycle, firstBilledDate = firstDate, lastBilledDate = lastDate, nextBillingDate = nextDate,
                    detectionSource = sorted.first().sourceType, priceChanged = priceChanged, priceChangePct = priceChangePct,
                    transactionIds = sorted.map { it.id }
                ))
            }
            detected += subscription
            logger.info("Detected subscription {} for user {} ({})", subscription.id, userId, cycle)
        }

        endStopped(userId, today, detected.map { it.id }.toSet())
        return detected
    }

    /** Active subscriptions not charged for two cycles (+ a week) are marked ended, with the reason. */
    private suspend fun endStopped(userId: UUID, today: LocalDate, stillCharging: Set<UUID>) {
        val active = subscriptionRepository.findByUserId(userId, status = SubscriptionStatus.ACTIVE, limit = 500)
        for (s in active) {
            if (s.id in stillCharging) continue
            val last = s.lastBilledDate ?: continue
            val due = nextCharge(nextCharge(last, s.billingCycle), s.billingCycle).plusDays(GRACE_DAYS)
            if (!due.isBefore(today)) continue
            subscriptionRepository.update(s.copy(
                status = SubscriptionStatus.CANCELLED, nextBillingDate = null,
                metadata = (s.metadata ?: emptyMap()) + mapOf(ENDED_BY to "detector", ENDED_REASON to "No charge since $last")
            ))
            logger.info("Subscription {} looks ended (no charge since {})", s.id, last)
        }
    }

    /** The catalogue's subcategory decides; then the charges' own category; else Other. */
    internal fun categoryFor(name: String, charges: List<Transaction>): SubscriptionCategory {
        catalogue.byBrand(name)?.let { e -> fromCatalogue(e.category, e.subcategory)?.let { return it } }
        val common = charges.groupingBy { it.category }.eachCount().maxByOrNull { it.value }?.key
        return when (common) {
            TransactionCategory.ENTERTAINMENT -> SubscriptionCategory.ENTERTAINMENT
            TransactionCategory.FOOD -> SubscriptionCategory.FOOD
            TransactionCategory.EDUCATION -> SubscriptionCategory.PRODUCTIVITY
            else -> SubscriptionCategory.OTHER
        }
    }

    companion object {
        /** Look back far enough for yearly plans (two charges ~365 days apart). */
        const val LOOKBACK_DAYS = 400L
        const val GRACE_DAYS = 7L
        private const val ENDED_BY = "endedBy"
        private const val ENDED_REASON = "endedReason"
        private val ENDED_KEYS = setOf(ENDED_BY, ENDED_REASON)

        fun computeIntervals(sortedTxns: List<Transaction>, zone: java.time.ZoneId = ZoneOffset.UTC): List<Double> {
            if (sortedTxns.size < 2) return emptyList()
            return sortedTxns.zipWithNext().map { (a, b) ->
                ChronoUnit.DAYS.between(a.transactedAt.atZone(zone).toLocalDate(), b.transactedAt.atZone(zone).toLocalDate()).toDouble()
            }
        }

        /** The next charge after [last], by the calendar (month ends stay month ends). */
        fun nextCharge(last: LocalDate, cycle: BillingCycle): LocalDate = when (cycle) {
            BillingCycle.DAILY -> last.plusDays(1)
            BillingCycle.WEEKLY -> last.plusWeeks(1)
            BillingCycle.MONTHLY -> last.plusMonths(1)
            BillingCycle.QUARTERLY -> last.plusMonths(3)
            BillingCycle.YEARLY -> last.plusYears(1)
        }

        /** Catalogue (category, subcategory) → subscription category; null when it says nothing. */
        fun fromCatalogue(category: TransactionCategory, subcategory: String?): SubscriptionCategory? = when {
            subcategory in setOf("streaming", "music", "movies_events", "tv") -> SubscriptionCategory.ENTERTAINMENT
            subcategory == "cloud" -> SubscriptionCategory.CLOUD
            subcategory == "fitness" -> SubscriptionCategory.FITNESS
            subcategory == "food_delivery" -> SubscriptionCategory.FOOD
            subcategory in setOf("apps", "courses") -> SubscriptionCategory.PRODUCTIVITY
            subcategory == "news" -> SubscriptionCategory.NEWS
            subcategory == "gaming" -> SubscriptionCategory.GAMING
            category == TransactionCategory.ENTERTAINMENT -> SubscriptionCategory.ENTERTAINMENT
            else -> null
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
