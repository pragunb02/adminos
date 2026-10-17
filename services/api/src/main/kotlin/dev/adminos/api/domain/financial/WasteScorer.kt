package dev.adminos.api.domain.financial

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/** A named reason a subscription may be money wasted (AOS-509). Stored by [code]; never renamed. */
enum class WasteFactor(val code: String) {
    PRICE_INCREASED("price_increased"),
    DUPLICATE_SERVICE("duplicate_service"),
    HIGH_COST_SHARE("high_cost_share"),
    LONG_RUNNING("long_running"),
    DORMANT_MANDATE("dormant_mandate"),
    TRIAL_ENDING("trial_ending"),
    MARKED_UNUSED("marked_unused"),
    /** Not a waste signal: says honestly that AdminOS can't see usage (0 points). */
    USAGE_UNKNOWN("usage_unknown");

    companion object {
        fun fromCode(code: String): WasteFactor = entries.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("Unknown waste factor '$code'")
    }
}

/** One factor's share of a score: [points] out of 100, and the sentence the user reads. */
data class WasteReason(val factor: WasteFactor, val points: Int, val reason: String) {
    init {
        require(points in 0..100) { "Waste points are 0–100, was $points" }
        require(reason.isNotBlank()) { "A waste reason needs a sentence" }
    }
}

/** What the scorer knows besides the subscription itself, gathered once per user. */
data class WasteContext(
    val today: LocalDate,
    /** The user's active and trial subscriptions (including the one scored). */
    val subscriptions: List<Subscription>,
    /** Net spending in the last 30 days; null or zero when unknown. */
    val monthlySpend: BigDecimal?,
    /** Payees (normalized, see `MandateService.normalize`) of active mandates with nothing taken for two cycles. */
    val dormantMandatePayees: List<String>
)

/** A score in [0, 1] (two decimals) and its reasons, strongest first. */
data class WasteAssessment(val score: BigDecimal, val reasons: List<WasteReason>) {
    init {
        require(score.signum() >= 0 && score <= BigDecimal.ONE) { "A waste score is between 0 and 1, was $score" }
    }

    /** Factors that actually added points (the usage note doesn't). */
    val factors: Set<WasteFactor> get() = reasons.filter { it.points > 0 }.map { it.factor }.toSet()
}

/**
 * Scores how likely a subscription is money wasted, from signals AdminOS really has: SMS and
 * statement charges, mandates and the user's own marks (AOS-509, D-58). Pure and deterministic: the
 * same subscription and context always give the same score and sentences.
 *
 * Each factor adds named points; the score is their sum (capped at 100) / 100. AdminOS can't see
 * whether a service is used (no app or login data), so it never guesses: it says "usage unknown"
 * unless the user marked it used or unused. Supersedes the Go `ComputeWasteScore` (Gmail login
 * signals that nothing produces).
 */
object WasteScorer {
    /** A score at or above this flags the subscription. */
    val FLAG_THRESHOLD = BigDecimal("0.70")

    const val TRIAL_ENDING_POINTS = 40
    const val TRIAL_ENDING_WITHIN_DAYS = 7L
    const val MARKED_UNUSED_POINTS = 40
    const val DORMANT_MANDATE_POINTS = 40
    const val DUPLICATE_SERVICE_POINTS = 35
    const val PRICE_INCREASED_POINTS = 30
    /** Extra points when the rise is at least [BIG_PRICE_RISE_PCT]. */
    const val BIG_PRICE_RISE_POINTS = 10
    val BIG_PRICE_RISE_PCT = BigDecimal("20")
    const val HIGH_COST_SHARE_POINTS = 25
    /** Monthly cost at or above this share of the month's spending counts as a big slice. */
    val HIGH_COST_SHARE_PCT = BigDecimal("5")
    const val LONG_RUNNING_POINTS = 15
    const val LONG_RUNNING_MONTHS = 12L

    /**
     * Groups that are the same kind of service, from the merchant catalogue's subcategory. "apps"
     * (Apple, Google Play, OpenAI…) is too broad to call two of them duplicates.
     */
    private val DUPLICATE_GROUPS = setOf("streaming", "music", "cloud", "books", "fitness", "courses")

    fun score(subscription: Subscription, context: WasteContext, catalogue: MerchantCatalogue = MerchantCatalogue.DEFAULT): WasteAssessment {
        val reasons = listOfNotNull(
            trialEnding(subscription, context.today),
            markedUnused(subscription),
            dormantMandate(subscription, context.dormantMandatePayees),
            duplicate(subscription, context.subscriptions, catalogue),
            priceIncreased(subscription),
            costShare(subscription, context.monthlySpend),
            longRunning(subscription, context.today)
        ).sortedByDescending { it.points } + listOfNotNull(usageUnknown(subscription))
        val points = reasons.sumOf { it.points }.coerceAtMost(100)
        return WasteAssessment(BigDecimal(points).divide(BigDecimal(100), 2, RoundingMode.UNNECESSARY), reasons)
    }

    /** The kind of service for duplicate checks: the subscription's category, else the catalogue's subcategory. */
    internal fun serviceGroup(s: Subscription, catalogue: MerchantCatalogue): String? {
        if (s.category != SubscriptionCategory.OTHER) return s.category.name.lowercase()
        val entry = catalogue.byBrand(s.merchantName) ?: catalogue.byBrand(s.name)
        return entry?.subcategory?.takeIf { it in DUPLICATE_GROUPS }
    }

    private fun trialEnding(s: Subscription, today: LocalDate): WasteReason? {
        if (s.status != SubscriptionStatus.TRIAL) return null
        val converts = s.nextBillingDate ?: return null
        val days = ChronoUnit.DAYS.between(today, converts)
        if (days !in 0..TRIAL_ENDING_WITHIN_DAYS) return null
        val whenText = when (days) { 0L -> "today"; 1L -> "tomorrow"; else -> "on $converts" }
        return WasteReason(WasteFactor.TRIAL_ENDING, TRIAL_ENDING_POINTS, "Free trial turns paid $whenText")
    }

    private fun markedUnused(s: Subscription): WasteReason? =
        if (s.usageStatus == UsageStatus.UNUSED) WasteReason(WasteFactor.MARKED_UNUSED, MARKED_UNUSED_POINTS, "Marked as not used") else null

    private fun dormantMandate(s: Subscription, dormantPayees: List<String>): WasteReason? {
        if (!DuesService.coveredByMandate(s, dormantPayees)) return null
        return WasteReason(WasteFactor.DORMANT_MANDATE, DORMANT_MANDATE_POINTS,
            "AutoPay for it is still active but nothing was taken for two cycles")
    }

    private fun duplicate(s: Subscription, all: List<Subscription>, catalogue: MerchantCatalogue): WasteReason? {
        val group = serviceGroup(s, catalogue) ?: return null
        val others = all.filter { it.id != s.id && serviceGroup(it, catalogue) == group }.map { it.name }.distinct().sorted()
        if (others.isEmpty()) return null
        return WasteReason(WasteFactor.DUPLICATE_SERVICE, DUPLICATE_SERVICE_POINTS,
            "You also pay for ${others.joinToString(", ")} (both $group)")
    }

    private fun priceIncreased(s: Subscription): WasteReason? {
        if (!s.priceChanged) return null
        val pct = s.priceChangePct
        if (pct != null && pct.signum() <= 0) return null // a price cut isn't waste
        if (pct == null) return WasteReason(WasteFactor.PRICE_INCREASED, PRICE_INCREASED_POINTS, "Price went up at the last charge")
        val points = PRICE_INCREASED_POINTS + if (pct >= BIG_PRICE_RISE_PCT) BIG_PRICE_RISE_POINTS else 0
        return WasteReason(WasteFactor.PRICE_INCREASED, points, "Price went up ${pct.stripTrailingZeros().toPlainString()}% at the last charge")
    }

    private fun costShare(s: Subscription, monthlySpend: BigDecimal?): WasteReason? {
        if (monthlySpend == null || monthlySpend.signum() <= 0) return null
        val monthly = s.billingCycle.monthlyCost(s.amount)
        val share = monthly.multiply(BigDecimal(100)).divide(monthlySpend, 2, RoundingMode.HALF_UP)
        if (share < HIGH_COST_SHARE_PCT) return null
        return WasteReason(WasteFactor.HIGH_COST_SHARE, HIGH_COST_SHARE_POINTS,
            "${rupees(monthly)} a month is ${share.setScale(0, RoundingMode.HALF_UP)}% of your spending in the last 30 days")
    }

    private fun longRunning(s: Subscription, today: LocalDate): WasteReason? {
        if (s.priceChanged) return null
        val since = s.firstBilledDate ?: return null
        val months = ChronoUnit.MONTHS.between(since, today)
        if (months < LONG_RUNNING_MONTHS) return null
        return WasteReason(WasteFactor.LONG_RUNNING, LONG_RUNNING_POINTS, "Paying for $months months without a change: worth a fresh look")
    }

    private fun usageUnknown(s: Subscription): WasteReason? =
        if (s.usageStatus == UsageStatus.UNKNOWN) WasteReason(WasteFactor.USAGE_UNKNOWN, 0, "Usage unknown: AdminOS can't see whether you use it") else null

    private fun rupees(amount: BigDecimal): String =
        "₹" + NumberFormat.getIntegerInstance(Locale.forLanguageTag("en-IN")).format(amount.setScale(0, RoundingMode.HALF_UP))

    /**
     * Applies a fresh [assessment] to [s] as of [now]: stores the score and reasons, and sets the flag.
     * A score at or above [FLAG_THRESHOLD] flags it, unless the user kept it and no factor is new
     * since then; below the threshold the flag goes. Running it twice gives the same result.
     */
    fun applyTo(s: Subscription, assessment: WasteAssessment, now: java.time.Instant): Subscription {
        val high = assessment.score >= FLAG_THRESHOLD
        val kept = s.flagDismissedAt != null
        val newSinceKept = assessment.factors - s.wasteFactorsDismissed
        val flag = high && (!kept || newSinceKept.isNotEmpty())
        val reasonText = assessment.reasons.filter { it.points > 0 }.take(3).joinToString("; ") { it.reason }
        return s.copy(
            wasteScore = assessment.score,
            wasteScoreUpdatedAt = now,
            wasteReasons = assessment.reasons,
            isFlagged = flag,
            flaggedReason = if (flag) reasonText else null,
            flaggedAt = if (flag) s.flaggedAt.takeIf { s.isFlagged } ?: now else null,
            // A new reason re-opens the question: the old "keep" no longer covers it.
            flagDismissedAt = if (flag) null else s.flagDismissedAt
        )
    }
}
