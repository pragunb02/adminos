package dev.adminos.api.domain.financial

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Everyday shopping (groceries, food, quick commerce) that repeats without being a subscription
 * (AOS-516). One implementation reads the merchant catalogue; the coach adds its habit lenses
 * (`LensEverydaySpending`), so the detector and the leaks page agree on what a grocery run is.
 */
fun interface EverydaySpending {
    fun isEveryday(t: Transaction): Boolean

    companion object {
        /** Everyday when any of [parts] says so. */
        fun anyOf(vararg parts: EverydaySpending) = EverydaySpending { t -> parts.any { it.isEveryday(t) } }
    }
}

/** Groceries, food delivery, restaurants and cafés by the catalogue's or the categoriser's subcategory, or a food category. */
class CatalogueEverydaySpending(private val catalogue: MerchantCatalogue = MerchantCatalogue.DEFAULT) : EverydaySpending {
    override fun isEveryday(t: Transaction): Boolean {
        val entry = catalogue.byBrand(t.merchantName)
        val subcategory = entry?.subcategory ?: t.subcategory
        return subcategory in EVERYDAY_SUBCATEGORIES || (entry?.category ?: t.category) == TransactionCategory.FOOD
    }

    companion object {
        val EVERYDAY_SUBCATEGORIES = setOf("groceries", "food_delivery", "restaurant", "cafe")
    }
}

/** Why charges do or don't look like a subscription (AOS-516). */
enum class SubscriptionSignal {
    /** A subscription service by the catalogue: streaming, music, cloud, apps, books, news, gaming, fitness, courses. */
    KNOWN_SERVICE,
    /** A prepaid plan: mobile, broadband or TV. */
    PLAN,
    /** Paid by a mandate or AutoPay. */
    MANDATE,
    /** The same amount each time, allowing one price change. */
    STABLE_AMOUNT,
    /** Groceries, food or quick commerce. */
    EVERYDAY,
    /** A loan, investment, insurance or wallet payment. */
    FINANCIAL,
    /** A merchant the catalogue knows as something else (shopping, transport, a utility bill…). */
    OTHER_KIND
}

/** The signals seen in a merchant's charges, and whether they make a subscription. [reason] says why not. */
data class SubscriptionEvidence(val signals: Set<SubscriptionSignal>, val isSubscription: Boolean, val reason: String) {
    init {
        require(reason.isNotBlank()) { "Evidence says why" }
    }
}

/**
 * Decides whether a regular merchant is a subscription (AOS-516). A steady interval alone isn't
 * enough: a weekly grocery run is steady too. In order:
 * 1. a loan, investment, insurance or wallet payment → no (mandates for those aren't subscriptions, D-56);
 * 2. amounts that vary → no;
 * 3. groceries, food or quick commerce → no, unless a mandate pays it (a membership on AutoPay);
 * 4. a known subscription service, a prepaid plan or a mandate → yes;
 * 5. a merchant the catalogue knows as something else → no;
 * 6. an unknown merchant charging the same amount, not categorised as food, shopping or transport → yes (a local gym).
 */
object SubscriptionSignals {
    val SERVICE_SUBCATEGORIES = setOf("streaming", "music", "cloud","apps", "books", "news", "gaming", "fitness", "courses")
    val PLAN_SUBCATEGORIES = setOf("mobile", "broadband", "tv")
    val FINANCIAL_SUBCATEGORIES = setOf("pay_later", "investment", "insurance", "wallet", "loan", "emi")
    private val FINANCIAL_CATEGORIES = setOf(TransactionCategory.EMI, TransactionCategory.TRANSFER)
    private val NOT_SERVICE_CATEGORIES = setOf(TransactionCategory.FOOD, TransactionCategory.SHOPPING, TransactionCategory.TRANSPORT)
    private val MANDATE_NARRATION = Regex("\\b(AUTO\\s?PAY|NACH|E-?MANDATE|MANDATE|STANDING\\s+INSTRUCTION)\\b", RegexOption.IGNORE_CASE)

    /** Two charges differ when they're more than this share apart, or [MIN_STEP] (whichever is larger). */
    val TOLERANCE = BigDecimal("0.10")
    val MIN_STEP = BigDecimal(10)
    /** Charges this small are card checks or free months, not the price. */
    val TOKEN_CHARGE = BigDecimal(2)

    fun assess(charges: List<Transaction>, everyday: EverydaySpending, catalogue: MerchantCatalogue = MerchantCatalogue.DEFAULT): SubscriptionEvidence {
        require(charges.isNotEmpty()) { "No charges to assess" }
        val latest = charges.maxBy { it.transactedAt }
        val entry = catalogue.byBrand(latest.merchantName)
        val subcategory = entry?.subcategory ?: mostCommon(charges.mapNotNull { it.subcategory })
        val category = entry?.category ?: mostCommon(charges.map { it.category }) ?: TransactionCategory.OTHER
        val signals = buildSet {
            if (subcategory in SERVICE_SUBCATEGORIES || category == TransactionCategory.SUBSCRIPTION) add(SubscriptionSignal.KNOWN_SERVICE)
            if (subcategory in PLAN_SUBCATEGORIES) add(SubscriptionSignal.PLAN)
            if (charges.any(::paidByMandate)) add(SubscriptionSignal.MANDATE)
            if (subcategory in FINANCIAL_SUBCATEGORIES || category in FINANCIAL_CATEGORIES) add(SubscriptionSignal.FINANCIAL)
            if (charges.count { everyday.isEveryday(it) } * 2 > charges.size) add(SubscriptionSignal.EVERYDAY)
            if (entry != null && SubscriptionSignal.KNOWN_SERVICE !in this && SubscriptionSignal.PLAN !in this) add(SubscriptionSignal.OTHER_KIND)
            if (isStable(charges.sortedBy { it.transactedAt }.map { it.amount }, vouched = vouches(this))) add(SubscriptionSignal.STABLE_AMOUNT)
        }
        fun no(why: String) = SubscriptionEvidence(signals, false, why)
        fun yes(why: String) = SubscriptionEvidence(signals, true, why)
        return when {
            SubscriptionSignal.FINANCIAL in signals -> no("a loan, investment, insurance or wallet payment")
            SubscriptionSignal.STABLE_AMOUNT !in signals -> no("the amounts vary")
            SubscriptionSignal.EVERYDAY in signals && SubscriptionSignal.MANDATE !in signals -> no("groceries, food or quick commerce")
            SubscriptionSignal.KNOWN_SERVICE in signals -> yes("a subscription service")
            SubscriptionSignal.PLAN in signals -> yes("a prepaid plan")
            SubscriptionSignal.MANDATE in signals -> yes("paid by a mandate")
            SubscriptionSignal.OTHER_KIND in signals -> no("${(subcategory ?: category.name.lowercase()).replace('_', ' ')} isn't a subscription service")
            category in NOT_SERVICE_CATEGORIES -> no("${category.name.lowercase()} spending")
            else -> yes("the same amount at regular intervals")
        }
    }

    /** A known service or a mandate vouches for charges, so their price may step up. */
    fun vouches(signals: Set<SubscriptionSignal>): Boolean = SubscriptionSignal.KNOWN_SERVICE in signals || SubscriptionSignal.MANDATE in signals

    /** Set by the mandate matcher (D-56) or the SMS parser, or said by the narration. */
    fun paidByMandate(t: Transaction): Boolean =
        t.isRecurring || t.recurringGroupId != null || t.metadata?.get("mandate") == "true" || t.metadata?.get("umn") != null ||
            t.merchantRaw?.let { MANDATE_NARRATION.containsMatchIn(it) } == true

    /**
     * The same price each time, allowing one change (a price rise) when there are three charges or
     * more, or two when [vouched] (a known service or a mandate vouches for them). A vouched
     * service may also rise more than once, as long as it only ever goes up: prices rise in steps,
     * a grocery bill goes up and down. Token charges (≤ ₹2: card checks, free months) are ignored.
     */
    fun isStable(amounts: List<BigDecimal>, vouched: Boolean = false): Boolean {
        val priced = amounts.filter { it > TOKEN_CHARGE }
        if (priced.size < 2) return true
        val steps = priced.zipWithNext().filter { (a, b) -> differ(a, b) }
        val allowed = if (priced.size >= 3 || vouched) 1 else 0
        return steps.size <= allowed || (vouched && steps.all { (a, b) -> b > a })
    }

    fun differ(a: BigDecimal, b: BigDecimal): Boolean =
        (a - b).abs() > a.min(b).multiply(TOLERANCE).setScale(2, RoundingMode.HALF_UP).max(MIN_STEP)

    private fun <T> mostCommon(values: List<T>): T? = values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
}

/** The direction of a plan switch: monthly → yearly ([UP]) or yearly → monthly ([DOWN]). */
enum class PlanSwitchDirection { UP, DOWN }

/** A plan switch at charge [index] of the sorted charges. */
data class PlanSwitch(val index: Int, val direction: PlanSwitchDirection) {
    /** The cycle a single charge after the switch implies. */
    val impliedCycle: BillingCycle get() = if (direction == PlanSwitchDirection.UP) BillingCycle.YEARLY else BillingCycle.MONTHLY
    /** The cycle before the switch, when it can't be measured. */
    val impliedPrevious: BillingCycle get() = if (direction == PlanSwitchDirection.UP) BillingCycle.MONTHLY else BillingCycle.YEARLY

    companion object {
        /**
         * A yearly plan costs about 10–12 months of the monthly one; with a discount or GST, 8–14 (the
         * same band as AOS-715's price-rise rule, which calls such a charge a plan switch, not a rise).
         */
        val RATIO: ClosedFloatingPointRange<Double> = 8.0..14.0
        private const val REFERENCE_CHARGES = 3

        /**
         * The latest charge that is 8–14× (or 1/14–1/8 of) both the median of the up-to-3 charges
         * before it and the charge just before it (so the switch is where the price moved, and one
         * odd charge in between doesn't count); null when none. Token charges (≤ ₹2) are skipped.
         */
        fun find(amounts: List<BigDecimal>): PlanSwitch? {
            for (i in amounts.indices.reversed()) {
                val now = amounts[i]
                if (now <= SubscriptionSignals.TOKEN_CHARGE) continue
                val before = amounts.subList(0, i).filter { it > SubscriptionSignals.TOKEN_CHARGE }.takeLast(REFERENCE_CHARGES)
                if (before.isEmpty()) continue
                val usual = direction(now, median(before))
                if (usual != null && usual == direction(now, before.last())) return PlanSwitch(i, usual)
            }
            return null
        }

        private fun direction(now: BigDecimal, usual: BigDecimal): PlanSwitchDirection? {
            val ratio = now.toDouble() / usual.toDouble()
            return when {
                ratio in RATIO -> PlanSwitchDirection.UP
                1 / ratio in RATIO -> PlanSwitchDirection.DOWN
                else -> null
            }
        }

        private fun median(values: List<BigDecimal>): BigDecimal {
            val s = values.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]).divide(BigDecimal(2), 2, RoundingMode.HALF_UP)
        }
    }
}
