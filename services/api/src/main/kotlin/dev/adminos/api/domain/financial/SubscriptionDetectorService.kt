package dev.adminos.api.domain.financial

import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.sqrt

/** A charge history's cycle: [current] are the charges on the plan [cycle] belongs to, [earlier] those before a [switch]. */
data class CycleReading(
    val cycle: BillingCycle,
    val current: List<Transaction>,
    val earlier: List<Transaction>,
    val switch: PlanSwitch?,
    /** The cycle before [switch], measured or implied by its direction. */
    val previousCycle: BillingCycle?
) {
    init {
        require(current.isNotEmpty()) { "A reading has charges on its plan" }
        require((switch == null) == (previousCycle == null)) { "Only a switch has a previous cycle" }
    }
}

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
 * - **Subscription-like only (AOS-516):** a steady interval isn't enough; [SubscriptionSignals] also
 *   needs stable amounts and a service, plan or mandate (or an unknown merchant), and never
 *   groceries, food or quick commerce ([EverydaySpending]) or a financial product.
 * - **Plan switches (AOS-516):** a charge 8–14× the usual one (or the reverse) moves the subscription
 *   to the new cycle (monthly ↔ yearly) instead of leaving it on the old one to be marked ended.
 */
class SubscriptionDetectorService(
    private val transactionRepository: TransactionRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val calendar: dev.adminos.api.domain.identity.UserCalendar,
    private val priceRises: PriceRiseRepository,
    private val aliases: dev.adminos.api.domain.financial.grouping.MerchantAliasRepository,
    private val catalogue: MerchantCatalogue = MerchantCatalogue.DEFAULT,
    /** Groceries, food and quick commerce repeat without being subscriptions (AOS-516); the app adds the habit lenses. */
    private val everyday: EverydaySpending = CatalogueEverydaySpending(catalogue)
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

        // Merchants the user merged in Review count as one (AOS-709).
        val resolver = dev.adminos.api.domain.financial.grouping.AliasResolver(aliases.aliases(userId))
        val groups = debits.mapNotNull { t -> resolver.key(t.merchantName)?.let { it to t } }
            .groupBy({ it.first }, { it.second })

        val detected = mutableListOf<Subscription>()
        for ((merchant, txns) in groups) {
            if (txns.size < 2) continue
            val sorted = txns.sortedBy { it.transactedAt }
            val reading = readCycle(sorted, zone) ?: continue
            val cycle = reading.cycle
            val evidence = SubscriptionSignals.assess(reading.current, everyday, catalogue)
            val earlierStable = SubscriptionSignals.isStable(reading.earlier.map { it.amount }, vouched = SubscriptionSignals.vouches(evidence.signals))
            if (!evidence.isSubscription || !earlierStable) {
                logger.debug("Regular charges for user {} are not a subscription: {}", userId, if (earlierStable) evidence.reason else "the amounts vary")
                continue
            }

            val latest = sorted.last().amount
            // One rule for the alert, the page's arrow and the waste score (AOS-715, D-74). A free month is not a charge.
            // A plan switch isn't a price change (AOS-516): compare only charges on the current plan.
            val change = PriceChangeRule.evaluate(reading.current.filter { it.amount.signum() > 0 }.map { Charge(it.id, it.amount, it.currency, it.transactedAt.atZone(zone).toLocalDate()) }, cycle)
            val priceChanged = change != null
            val priceChangePct = change?.pct

            val firstDate = sorted.first().transactedAt.atZone(zone).toLocalDate()
            val lastDate = sorted.last().transactedAt.atZone(zone).toLocalDate()
            val nextDate = nextCharge(lastDate, cycle)
            val displayName = resolver.canonicalNameOf(merchant) ?: sorted.last().merchantName?.trim()?.takeIf { it.isNotEmpty() } ?: merchant
            val category = categoryFor(displayName, sorted)
            val switchedMetadata = reading.switch?.let {
                mapOf(PLAN_SWITCHED_FROM to reading.previousCycle?.name?.lowercase(),
                      PLAN_SWITCHED_ON to reading.current.first().transactedAt.atZone(zone).toLocalDate().toString())
            }.orEmpty()

            val members = resolver.membersOf(merchant)
            // After a merge, the subscription found under a merged name carries on under the kept one (AOS-709).
            // After a plan switch the subscription is the same one on a new cycle: move it, don't start another (AOS-516).
            val existing = subscriptionRepository.findByUserAndMerchantAndCycle(userId, merchant, cycle)
                ?: members.sorted().firstNotNullOfOrNull { subscriptionRepository.findByUserAndMerchantAndCycle(userId, it, cycle) }
                ?: reading.previousCycle?.let { subscriptionRepository.findByUserAndMerchantAndCycle(userId, merchant, it) }
            val subscription = if (existing != null) {
                if (existing.billingCycle != cycle) logger.info("Subscription {} switched plan: {} → {}", existing.id, existing.billingCycle, cycle)
                subscriptionRepository.update(existing.copy(
                    billingCycle = cycle,
                    merchantName = merchant, name = if (existing.merchantName == merchant) existing.name else displayName,
                    amount = latest, lastBilledDate = lastDate, nextBillingDate = nextDate,
                    priceChanged = priceChanged, priceChangePct = priceChangePct, transactionIds = sorted.map { it.id },
                    // A new charge after it looked ended (or a user cancel) means it's charging again.
                    status = if (existing.status == SubscriptionStatus.TRIAL) SubscriptionStatus.TRIAL else SubscriptionStatus.ACTIVE,
                    category = if (existing.category == SubscriptionCategory.OTHER) category else existing.category,
                    metadata = ((existing.metadata?.minus(ENDED_KEYS) ?: emptyMap()) + switchedMetadata).takeIf { it.isNotEmpty() }
                ))
            } else {
                subscriptionRepository.save(Subscription(
                    userId = userId, name = displayName, merchantName = merchant, category = category, amount = latest,
                    billingCycle = cycle, firstBilledDate = firstDate, lastBilledDate = lastDate, nextBillingDate = nextDate,
                    detectionSource = sorted.first().sourceType, priceChanged = priceChanged, priceChangePct = priceChangePct,
                    transactionIds = sorted.map { it.id }, metadata = switchedMetadata.takeIf { it.isNotEmpty() }
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
     * The billing cycle of [sorted] charges, after the latest plan switch if there was one (AOS-516).
     * A switch's earlier charges must have run on the opposite cycle (monthly before a yearly charge);
     * otherwise the history is read as a whole, as before. With only one charge on the new plan, the
     * switch's direction gives the cycle.
     */
    internal fun readCycle(sorted: List<Transaction>, zone: java.time.ZoneId): CycleReading? {
        fun cycleOf(charges: List<Transaction>): BillingCycle? =
            computeIntervals(charges, zone).takeIf { it.isNotEmpty() }?.let { determineBillingCycle(it.average(), stddev(it)) }
        val whole = { cycleOf(sorted)?.let { CycleReading(it, sorted, emptyList(), null, null) } }
        val switch = PlanSwitch.find(sorted.map { it.amount }) ?: return whole()
        val earlier = sorted.subList(0, switch.index)
        val current = sorted.subList(switch.index, sorted.size)
        val earlierCycle = if (earlier.size >= 2) cycleOf(earlier) else null
        if (earlier.size >= 2 && earlierCycle != switch.impliedPrevious) return whole()
        val cycle = if (current.size >= 2) cycleOf(current) ?: return null else switch.impliedCycle
        return CycleReading(cycle, current, earlier, switch, earlierCycle ?: switch.impliedPrevious)
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
        const val PLAN_SWITCHED_FROM = "planSwitchedFrom"
        const val PLAN_SWITCHED_ON = "planSwitchedOn"

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
