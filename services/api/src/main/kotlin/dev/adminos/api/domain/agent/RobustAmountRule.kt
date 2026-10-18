package dev.adminos.api.domain.agent

import dev.adminos.api.domain.agent.baselines.AmountScale
import dev.adminos.api.domain.agent.baselines.AmountStats
import dev.adminos.api.domain.agent.baselines.Baseline
import dev.adminos.api.domain.agent.baselines.BaselineModel
import dev.adminos.api.domain.agent.baselines.MeanMultipleBaseline
import dev.adminos.api.domain.agent.baselines.MedianMadBaseline
import dev.adminos.api.domain.agent.baselines.ToleranceLevel
import dev.adminos.api.domain.common.formatInr
import dev.adminos.api.domain.common.workflow.RuleResult
import dev.adminos.api.domain.financial.MerchantKey
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionNature
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/** Whose earlier spends a baseline is fitted on: the same merchant's, or the same category's. */
enum class BaselineScope {
    MERCHANT,
    /** Every earlier spend in the category. */
    CATEGORY,
    /**
     * One typical (median) amount per other merchant in the category: "what a place of this kind usually
     * costs", so a chai stall paid thirty times a month doesn't outvote the restaurants.
     */
    CATEGORY_MERCHANTS
}

/** One link of the fallback chain: which earlier spends, how far back (in the user's days), and which model. */
data class BaselineTier(val scope: BaselineScope, val lookbackDays: Long, val model: BaselineModel) {
    init {
        require(lookbackDays in 1..LONGEST_LOOKBACK_DAYS) { "A look-back is 1..$LONGEST_LOOKBACK_DAYS days" }
    }

    companion object {
        const val LONGEST_LOOKBACK_DAYS = 400L
    }
}

/** What the rule looks at besides the spend: the user's earlier spends, their zone, and this merchant's tolerance. */
data class AmountContext(
    /** Earlier spends of the user; anything else in it (later, other currency, other nature, the spend itself) is ignored. */
    val history: List<Transaction>,
    val zone: ZoneId,
    val tolerance: ToleranceLevel = ToleranceLevel.DEFAULT
)

/** Which tier judged the spend and what it learnt (also used by the eval). */
data class AmountJudgement(val tier: BaselineTier, val baseline: Baseline, val unusual: Boolean)

/**
 * Is this spend unusually large for this user? (AOS-713, D-73; replaces the 3× category average.)
 *
 * The [tiers] are tried in order; the **first one with enough history decides**, so a merchant with a
 * known pattern is judged by that pattern, not by its whole category. The default chain is:
 * 1. this merchant's payments in the last 180 days, median/MAD on log amounts, at least 5;
 * 2. this merchant's 1–4 earlier payments: over 3× their mean (a flight or a hotel isn't judged by cab rides);
 * 3. a first payment somewhere: the typical amount of each other place in the category, median/MAD on
 *    log amounts, at least 5 places (so a chai stall paid daily doesn't make every dinner "unusual");
 * 4. today's rule: over 3× the category's mean in the last 30 days, at least 3.
 *
 * Below [minimumAmount] nothing is unusual (D-53: a big chai isn't worth an alert). Only spends in the
 * same currency count; refunds, reversals and transfers never enter a baseline.
 */
class RobustAmountRule(
    private val tiers: List<BaselineTier> = DEFAULT_TIERS,
    private val minimumAmount: BigDecimal = MINIMUM_AMOUNT
) {
    init {
        require(tiers.isNotEmpty()) { "The amount rule needs at least one tier" }
        require(minimumAmount.signum() >= 0) { "The minimum amount is never negative" }
    }

    /** The longest look-back of any tier: how much history the caller must fetch. */
    val lookbackDays: Long = tiers.maxOf { it.lookbackDays }

    /** @return the tier that judged [subject] and its verdict, or null when no tier had enough history */
    fun judge(subject: Transaction, context: AmountContext): AmountJudgement? {
        val earlier = context.history.filter { it.isComparableEarlierSpend(subject) }
        for (tier in tiers) {
            val amounts = earlier.amountsFor(tier, subject, context.zone) ?: continue
            val baseline = tier.model.fit(amounts) ?: continue
            return AmountJudgement(tier, baseline, baseline.isUnusual(subject.amount, context.tolerance))
        }
        return null
    }

    /** @return a finding when [subject] is unusually large, else null */
    fun evaluate(subject: Transaction, context: AmountContext): RuleResult? {
        if (subject.nature != TransactionNature.SPEND || subject.amount < minimumAmount) return null
        val judgement = judge(subject, context)?.takeIf { it.unusual } ?: return null
        return RuleResult(ruleName = NAME, confidence = CONFIDENCE, reason = reason(subject, judgement))
    }

    private fun Transaction.isComparableEarlierSpend(subject: Transaction) =
        id != subject.id && nature == TransactionNature.SPEND && currency == subject.currency &&
            amount.signum() > 0 && transactedAt.isBefore(subject.transactedAt)

    /** The amounts [tier] fits on, or null when the tier can't apply (a merchant tier for a spend with no merchant). */
    private fun List<Transaction>.amountsFor(tier: BaselineTier, subject: Transaction, zone: ZoneId): List<BigDecimal>? {
        val since = windowStart(subject.transactedAt, tier.lookbackDays, zone)
        val recent = filter { !it.transactedAt.isBefore(since) }
        val key = MerchantKey.of(subject.merchantName)
        return when (tier.scope) {
            BaselineScope.MERCHANT -> key?.let { k -> recent.filter { MerchantKey.of(it.merchantName) == k }.map { it.amount } }
            BaselineScope.CATEGORY -> recent.filter { it.category == subject.category }.map { it.amount }
            BaselineScope.CATEGORY_MERCHANTS -> recent
                .filter { it.category == subject.category }
                .mapNotNull { t -> MerchantKey.of(t.merchantName)?.takeIf { it != key }?.let { it to t.amount } }
                .groupBy({ it.first }, { it.second }).values.map { AmountStats.median(it) }
        }
    }

    private fun reason(subject: Transaction, j: AmountJudgement): String {
        val where = when (j.tier.scope) {
            BaselineScope.MERCHANT -> "at ${subject.merchantName.orEmpty().trim()}"
            BaselineScope.CATEGORY, BaselineScope.CATEGORY_MERCHANTS -> "for ${subject.category.name.lowercase().replaceFirstChar { it.uppercase() }}"
        }
        val what = when (j.tier.scope) {
            BaselineScope.MERCHANT -> "payment"
            BaselineScope.CATEGORY -> "spend"
            BaselineScope.CATEGORY_MERCHANTS -> "place"
        } + if (j.baseline.sampleSize == 1) "" else "s"
        return "${subject.amount.formatInr()} is more than usual $where: usually ${j.baseline.usualRange.text()} " +
            "(${j.baseline.sampleSize} $what in ${j.tier.lookbackDays} days)"
    }

    companion object {
        /** The rule's name in anomalies, tolerances and type mapping; unchanged from AOS-505 so old rows read alike. */
        const val NAME = "large_amount"
        const val CONFIDENCE = 0.7
        /** Large-amount alerts only from ₹2,000 (D-53). */
        val MINIMUM_AMOUNT = BigDecimal(2000)

        /** Chosen on the synthetic streams (AOS-713, D-73): each tier was measured with and without (see the ticket). */
        val DEFAULT_TIERS: List<BaselineTier> = listOf(
            BaselineTier(BaselineScope.MERCHANT, 180, MedianMadBaseline(minHistory = 5, scale = AmountScale.LOG)),
            BaselineTier(BaselineScope.MERCHANT, 180, MeanMultipleBaseline(minHistory = 1, multiple = 3)),
            BaselineTier(BaselineScope.CATEGORY_MERCHANTS, 180, MedianMadBaseline(minHistory = 5, scale = AmountScale.LOG)),
            BaselineTier(BaselineScope.CATEGORY, 30, MeanMultipleBaseline(minHistory = 3, multiple = 3))
        )

        /** The AOS-505 rule alone: 3× the 30-day category mean (for the eval's "old" numbers). */
        val LEGACY_TIERS: List<BaselineTier> = listOf(BaselineTier(BaselineScope.CATEGORY, 30, MeanMultipleBaseline(minHistory = 3, multiple = 3)))

        /** Start of the user's day [days] before [at]'s day: windows are the user's dates, not UTC's. */
        fun windowStart(at: Instant, days: Long, zone: ZoneId): Instant =
            at.atZone(zone).toLocalDate().minusDays(days).atStartOfDay(zone).toInstant()
    }
}
