package dev.adminos.api.domain.coach.leaks

import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.coach.lenses.HabitLens
import dev.adminos.api.domain.coach.lenses.LateNightWindow
import dev.adminos.api.domain.coach.lenses.LensId
import dev.adminos.api.domain.coach.lenses.LensOverride
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * One counted transaction as the leaks see it (AOS-902): its lenses and its local time, worked out once.
 * [timeKnown] is false for statement imports, which carry a date but no time (stored at midday).
 */
data class LeakFact(
    val transaction: Transaction,
    val lenses: Set<LensId>,
    val local: LocalDateTime,
    val timeKnown: Boolean,
    /** A UPI payment to a person (D-81): never a habit, never in a category limit. */
    val person: Boolean = false
) {
    val nature: TransactionNature get() = transaction.nature
    val amount: BigDecimal get() = transaction.amount
    val isOrder: Boolean get() = nature == TransactionNature.SPEND
    /** The merchant as the console shows it ("Unknown" when there is none). */
    val merchant: String get() = transaction.merchantName?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_MERCHANT
    val merchantKey: String get() = LensOverride.keyOf(merchant)
    val weekday: DayOfWeek get() = local.dayOfWeek
    /** Spend adds, a refund subtracts (D-39/D-46). */
    val signed: BigDecimal get() = if (nature == TransactionNature.REFUND) amount.negate() else amount

    companion object {
        const val UNKNOWN_MERCHANT = "Unknown"
    }
}

data class LateNight(val orders: Int, val timedOrders: Int, val spent: BigDecimal) {
    /** Late-night orders ÷ orders with a known time, 4 decimals; null when no order had a time. */
    val share: BigDecimal? get() =
        if (timedOrders == 0) null else BigDecimal(orders).divide(BigDecimal(timedOrders), 4, RoundingMode.HALF_UP)
}

data class WeekdaySpend(val day: DayOfWeek, val orders: Int, val spent: BigDecimal)

/** The numbers for one group of facts (a lens, a merchant, or all lenses together). */
data class LeakNumbers(
    val spent: BigDecimal,
    val orders: Int,
    /** Total of the orders before refunds. */
    val ordered: BigDecimal,
    val lateNight: LateNight,
    val weekdays: List<WeekdaySpend>,
    val previousSpent: BigDecimal
) {
    /** An order's typical size: the orders' total before refunds ÷ their count; null with no orders. */
    val averageOrder: BigDecimal? get() = if (orders == 0) null else ordered.divide(BigDecimal(orders), 2, RoundingMode.HALF_UP)

    /** % change in spent vs the previous period, 1 decimal; null when there was nothing (or only refunds) before. */
    val changePct: BigDecimal? get() =
        previousSpent.takeIf { it.signum() > 0 }?.let { (spent - it).multiply(BigDecimal(100)).divide(it, 1, RoundingMode.HALF_UP) }
}

data class MerchantLeak(val merchant: String, val numbers: LeakNumbers)

data class LensLeak(val lens: HabitLens, val numbers: LeakNumbers, val merchants: List<MerchantLeak>)

data class LeakReport(
    val range: DateRange,
    val previousRange: DateRange,
    val lateNight: LateNightWindow,
    /** All lenses together: each transaction once, even when it sits in two lenses. */
    val total: LeakNumbers,
    val lenses: List<LensLeak>
)

/**
 * Which transactions a number is made of (AOS-902): the leaks page and its drill-down both use it,
 * so a number and the list behind it can't disagree. Null fields don't filter.
 */
data class LeakFilter(
    /** One lens; null = any lens (all leaks). */
    val lens: LensId? = null,
    /** Exact merchant as shown, case-insensitive ("Unknown" = no merchant). */
    val merchant: String? = null,
    /** Only late-night orders with a known time. */
    val lateNight: Boolean = false,
    val weekday: DayOfWeek? = null,
    val category: TransactionCategory? = null,
    val nature: TransactionNature? = null,
    /** Substring of the merchant, like Activity's search. */
    val search: String? = null
) {
    init {
        require(merchant == null || merchant.isNotBlank()) { "merchant: can't be blank" }
        require(nature == null || nature == TransactionNature.SPEND || nature == TransactionNature.REFUND) {
            "nature: only spend or refund are in the leaks"
        }
    }

    fun accepts(f: LeakFact, window: LateNightWindow): Boolean =
        (if (lens == null) f.lenses.isNotEmpty() else lens in f.lenses) &&
            (merchant == null || f.merchantKey == LensOverride.keyOf(merchant)) &&
            (!lateNight || (f.isOrder && f.timeKnown && f.local.toLocalTime() in window)) &&
            (weekday == null || f.weekday == weekday) &&
            (category == null || f.transaction.category == category) &&
            (nature == null || f.nature == nature) &&
            (search.isNullOrBlank() || f.merchant.contains(search.trim(), ignoreCase = true))
}
