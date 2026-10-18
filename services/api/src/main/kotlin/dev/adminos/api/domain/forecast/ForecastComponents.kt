package dev.adminos.api.domain.forecast

import dev.adminos.api.domain.analytics.DateRange
import java.math.BigDecimal
import java.time.LocalDate

/** Where a known future payment comes from (D-54, D-55, D-56). */
enum class DueSource { BILL, RENEWAL, MANDATE }

/**
 * A payment we already know about: an unpaid bill, an expected subscription renewal, or a mandate
 * debit. [date] is when it's expected; an overdue bill is expected as soon as possible, so the
 * loader dates it tomorrow and marks it [overdue].
 */
data class KnownDue(val source: DueSource, val name: String, val date: LocalDate, val amount: BigDecimal, val overdue: Boolean = false) {
    init {
        require(name.isNotBlank()) { "A due has a name" }
        require(amount.signum() > 0) { "A due is a positive amount" }
    }
}

/** What one component expects to go out over a future window, and how it got there ("How we got this"). */
sealed interface ComponentEstimate {
    val amount: BigDecimal

    /** Known payments from one source, each listed. */
    data class Dues(val source: DueSource, val items: List<KnownDue>) : ComponentEstimate {
        override val amount: BigDecimal get() = items.fold(BigDecimal.ZERO) { acc, d -> acc + d.amount }
    }

    /** Everyday spending: a daily rate per kind of day × the number of such days in the window. */
    data class RunRate(val fit: RunRateFit, val days: Map<DayType, Int>) : ComponentEstimate {
        override val amount: BigDecimal
            get() = days.entries.fold(BigDecimal.ZERO) { acc, (type, n) -> acc + fit.rateOf(type).multiply(BigDecimal(n)) }.money()
    }
}

/**
 * One source of expected spending over a window of future days (AOS-714). A new source (planned
 * purchases, a goal, a known trip) is a new implementation registered with the forecaster; the
 * forecaster just adds the parts up.
 */
fun interface ForecastComponent {
    /** What this source expects over [window], which only holds future days. An empty window (`null`) expects nothing. */
    fun estimate(window: DateRange?): ComponentEstimate
}

/** Known payments of one [source], counted on their expected dates. */
class KnownDuesComponent(private val source: DueSource, dues: List<KnownDue>) : ForecastComponent {
    private val dues = dues.filter { it.source == source }.sortedWith(compareBy<KnownDue> { it.date }.thenBy { it.name.lowercase() })

    override fun estimate(window: DateRange?): ComponentEstimate.Dues =
        ComponentEstimate.Dues(source, if (window == null) emptyList() else dues.filter { !it.date.isBefore(window.from) && !it.date.isAfter(window.to) })
}

/** Everyday (discretionary) spending at the fitted daily rates. */
class RunRateComponent(private val fit: RunRateFit) : ForecastComponent {
    override fun estimate(window: DateRange?): ComponentEstimate.RunRate =
        ComponentEstimate.RunRate(fit, DayType.entries.associateWith { type -> window?.let { countDays(it, type) } ?: 0 })

    companion object {
        fun countDays(window: DateRange, type: DayType): Int =
            generateSequence(window.from) { it.plusDays(1) }.takeWhile { !it.isAfter(window.to) }.count { DayType.of(it) == type }
    }
}

/** The components for a forecast: one per due source, then projected monthly bills (AOS-721), then everyday spending. */
fun componentsFor(dues: List<KnownDue>, fit: RunRateFit, projected: List<ProjectedBill> = emptyList()): List<ForecastComponent> =
    DueSource.entries.map { KnownDuesComponent(it, dues) } + ProjectedBillsComponent(projected) + RunRateComponent(fit)

internal fun BigDecimal.money(): BigDecimal = setScale(2, java.math.RoundingMode.HALF_UP)
