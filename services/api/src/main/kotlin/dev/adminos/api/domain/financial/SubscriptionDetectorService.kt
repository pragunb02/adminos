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
    private val priceRises: PriceRiseRepository,
    private val aliases: dev.adminos.api.domain.financial.grouping.MerchantAliasRepository,
    private val catalogue: MerchantCatalogue = MerchantCatalogue.DEFAULT
) {
    private val logger = LoggerFactory.getLogger(SubscriptionDetectorService::class.java)

    /** Detects and updates the user's subscriptions; also ends ones whose charges stopped. @return those seen charging */
    suspend fun detectSubscriptions(userId: UUID): List<Subscription> {
        val zone = calendar.zoneOf(userId)
        val today = calendar.today(userId)
        val since = today.minusDays(LOOKBACK_DAYS).atStartOfDay(zone).toInstant()
        val debits = transactionRepository.findDebitsByUserIdSince(userId, since)

        // Merchants the user merged in Review count as one (AOS-709).
        val resolver = dev.adminos.api.domain.financial.grouping.AliasResolver(aliases.aliases(userId))
        val groups = debits.mapNotNull { t -> resolver.key(t.merchantName)?.let { it to t } }
            .groupBy({ it.first }, { it.second })

        val detected = mutableListOf<Subscription>()
        for ((merchant, txns) in groups) {
            if (txns.size < 2) continue
            val sorted = txns.sortedBy { it.transactedAt }
            val intervals = computeIntervals(sorted, zone)
            if (intervals.isEmpty()) continue
            val cycle = determineBillingCycle(intervals.average(), stddev(intervals)) ?: continue

            val latest = sorted.last().amount
            // One rule for the alert, the page's arrow and the waste score (AOS-715, D-74). A free month is not a charge.
            val change = PriceChangeRule.evaluate(sorted.filter { it.amount.signum() > 0 }.map { Charge(it.id, it.amount, it.currency, it.transactedAt.atZone(zone).toLocalDate()) }, cycle)
            val priceChanged = change != null
            val priceChangePct = change?.pct

            val firstDate = sorted.first().transactedAt.atZone(zone).toLocalDate()
            val lastDate = sorted.last().transactedAt.atZone(zone).toLocalDate()
            val nextDate = nextCharge(lastDate, cycle)
            val displayName = resolver.canonicalNameOf(merchant) ?: sorted.last().merchantName?.trim()?.takeIf { it.isNotEmpty() } ?: merchant
            val category = categoryFor(displayName, sorted)

            val members = resolver.membersOf(merchant)
            // After a merge, the subscription found under a merged name carries on under the kept one.
            val existing = subscriptionRepository.findByUserAndMerchantAndCycle(userId, merchant, cycle)
                ?: members.sorted().firstNotNullOfOrNull { subscriptionRepository.findByUserAndMerchantAndCycle(userId, it, cycle) }
            val subscription = if (existing != null) {
                subscriptionRepository.update(existing.copy(
                    merchantName = merchant, name = if (existing.merchantName == merchant) existing.name else displayName,
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
            endMerged(userId, subscription, members, displayName)
            change?.takeIf { it.isRise }?.let { recordRise(subscription, it) }
            detected += subscription
            logger.info("Detected subscription {} for user {} ({})", subscription.id, userId, cycle)
        }

        endStopped(userId, today, detected.map { it.id }.toSet())
        return detected
    }

    /**
     * A merged name's own subscription (same cycle) is now part of [kept]: it ends, saying so, so the waste
     * score no longer sees a duplicate (AOS-709). Its history stays.
     */
    private suspend fun endMerged(userId: UUID, kept: Subscription, members: Set<String>, keptName: String) {
        for (key in members.sorted()) {
            val old = subscriptionRepository.findByUserAndMerchantAndCycle(userId, key, kept.billingCycle) ?: continue
            if (old.id == kept.id || old.status == SubscriptionStatus.CANCELLED) continue
            subscriptionRepository.update(old.copy(
                status = SubscriptionStatus.CANCELLED, nextBillingDate = null, isFlagged = false, flaggedReason = null,
                metadata = (old.metadata ?: emptyMap()) + mapOf(ENDED_BY to "merge", ENDED_REASON to "Merged into $keptName", MERGED_INTO to kept.id.toString())
            ))
            logger.info("Subscription {} merged into {}", old.id, kept.id)
        }
    }

    /** Records a rise once per (subscription, new price); [dev.adminos.api.domain.notifications.PriceRiseAlerts] tells the user. */
    private suspend fun recordRise(subscription: Subscription, change: PriceChange) {
        if (priceRises.recordIfNew(PriceRise.of(subscription.userId, subscription.id, change))) {
            logger.info("Subscription {} price rose {}% at transaction {}", subscription.id, change.pct, change.transactionId)
        }
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
        private const val MERGED_INTO = "mergedInto"
        private val ENDED_KEYS = setOf(ENDED_BY, ENDED_REASON, MERGED_INTO)

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
