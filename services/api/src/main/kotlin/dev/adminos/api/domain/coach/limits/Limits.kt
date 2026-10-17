package dev.adminos.api.domain.coach.limits

import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.coach.leaks.LeakFact
import dev.adminos.api.domain.coach.lenses.LensId
import dev.adminos.api.domain.coach.lenses.LensOverride
import dev.adminos.api.domain.financial.TransactionCategory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/** What a limit counts (AOS-904). Each scope decides which leak facts are its spending. */
sealed interface LimitScope {
    val kind: Kind
    /** The stored value: a lens id, a merchant key, or a category name (lower case). */
    val value: String
    fun counts(f: LeakFact): Boolean

    enum class Kind { LENS, MERCHANT, CATEGORY }

    /** A habit lens; person-to-person payments are already outside every lens (D-81). */
    data class Lens(val id: LensId) : LimitScope {
        override val kind = Kind.LENS
        override val value: String get() = id.value
        override fun counts(f: LeakFact) = id in f.lenses
    }

    /** One merchant exactly, as named by the owner: counts even if it looks like a person. */
    data class Merchant(val name: String) : LimitScope {
        init {
            require(name.isNotBlank() && name.trim().length <= LensOverride.MAX_MERCHANT) { "merchant: 1..${LensOverride.MAX_MERCHANT} characters" }
        }
        override val kind = Kind.MERCHANT
        override val value: String get() = LensOverride.keyOf(name)
        override fun counts(f: LeakFact) = f.merchantKey == value
    }

    /** A spending category; settle-ups with people are left out like everywhere in the coach. */
    data class Category(val category: TransactionCategory) : LimitScope {
        init {
            require(category != TransactionCategory.TRANSFER) { "category: transfers aren't spending" }
        }
        override val kind = Kind.CATEGORY
        override val value: String get() = category.name.lowercase()
        override fun counts(f: LeakFact) = !f.person && f.transaction.category == category
    }
}

/** Weeks run Monday–Sunday and months are calendar months, in the user's zone (D-46). */
enum class LimitPeriod {
    WEEKLY, MONTHLY;

    fun rangeOf(day: LocalDate): DateRange = when (this) {
        WEEKLY -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { DateRange(it, it.plusDays(6)) }
        MONTHLY -> DateRange(day.withDayOfMonth(1), day.with(TemporalAdjusters.lastDayOfMonth()))
    }

    val word: String get() = if (this == WEEKLY) "weekly" else "monthly"
}

/** One limit the owner set or accepted. Only accepted limits exist: suggestions are never stored as limits. */
data class SpendingLimit(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val scope: LimitScope,
    /** For display: the lens label, merchant name or category as the owner saw it. */
    val label: String,
    val period: LimitPeriod,
    val amount: BigDecimal,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    init {
        require(amount.signum() > 0) { "amount: must be more than 0" }
        require(amount.scale() <= 2) { "amount: at most 2 decimal places" }
        require(amount <= MAX_AMOUNT) { "amount: at most $MAX_AMOUNT" }
        require(label.isNotBlank() && label.length <= 255) { "label: 1..255 characters" }
    }

    companion object {
        val MAX_AMOUNT = BigDecimal("10000000.00")
    }
}

/** 50%, 80% and 100% of a limit (AOS-904, D-80): each pushes at most once per period. */
enum class Threshold(val percent: Int) {
    HALF(50), MOST(80), ALL(100);

    fun reachedBy(spent: BigDecimal, amount: BigDecimal): Boolean =
        spent.multiply(BigDecimal(100)) >= amount.multiply(BigDecimal(percent))
}

/** Where a limit stands in the period containing [today]. */
data class LimitStatus(val limit: SpendingLimit, val period: DateRange, val today: LocalDate, val spent: BigDecimal) {
    /** Spent ÷ amount, 4 decimals; can be over 1, or below 0 with refunds only. */
    val used: BigDecimal get() = spent.divide(limit.amount, 4, RoundingMode.HALF_UP)
    val left: BigDecimal get() = limit.amount - spent
    val reached: List<Threshold> get() = Threshold.entries.filter { it.reachedBy(spent, limit.amount) }
    /** Days left in the period after today. */
    val daysLeft: Long get() = java.time.temporal.ChronoUnit.DAYS.between(today, period.to)
}

object LimitMath {
    fun spent(scope: LimitScope, facts: List<LeakFact>): BigDecimal = facts.filter { scope.counts(it) }.fold(BigDecimal.ZERO) { a, f -> a + f.signed }

    fun status(limit: SpendingLimit, today: LocalDate, factsInPeriod: List<LeakFact>): LimitStatus =
        LimitStatus(limit, limit.period.rangeOf(today), today, spent(limit.scope, factsInPeriod))
}
