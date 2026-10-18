package dev.adminos.api.domain.forecast

import dev.adminos.api.domain.analytics.DateRange
import java.math.BigDecimal
import java.time.LocalDate
import java.util.SortedMap
import java.util.TreeMap

/**
 * Net spending (spend − refunds, D-39/D-46) on one local day of the user, split into what known
 * recurring sources explain (mandate debits, bill payments, subscription charges) and the rest.
 * The rest is what the run-rate learns from; the recurring part is forecast as known dues instead,
 * so nothing is counted twice (AOS-714).
 */
data class SpendDay(val day: LocalDate, val discretionary: BigDecimal, val recurring: BigDecimal) {
    val total: BigDecimal get() = discretionary + recurring
}

/** One credit counted as income (D-39), with the payer's name when the source gave one. */
data class IncomeCredit(val day: LocalDate, val payer: String?, val amount: BigDecimal) {
    init {
        require(amount.signum() > 0) { "An income credit is a positive amount" }
    }
}

/**
 * A user's spending by local day. Days without a row spent nothing. [firstDay] is the user's first
 * recorded activity of any kind (including transfers), which is where "history" starts: a day before it
 * is unknown, not zero.
 */
class SpendHistory(days: Collection<SpendDay>, val firstDay: LocalDate) {
    private val byDay: SortedMap<LocalDate, SpendDay> = TreeMap<LocalDate, SpendDay>().apply {
        days.forEach { d ->
            val prev = this[d.day]
            this[d.day] = if (prev == null) d else SpendDay(d.day, prev.discretionary + d.discretionary, prev.recurring + d.recurring)
        }
    }

    init {
        require(byDay.isEmpty() || !byDay.firstKey().isBefore(firstDay)) { "Spending can't start before the first activity" }
    }

    /** Whether [day] is inside the known history. */
    fun known(day: LocalDate): Boolean = !day.isBefore(firstDay)

    fun discretionaryOn(day: LocalDate): BigDecimal = byDay[day]?.discretionary ?: BigDecimal.ZERO

    fun totalOn(day: LocalDate): BigDecimal = byDay[day]?.total ?: BigDecimal.ZERO

    /** Σ total spending over [range] (inclusive). */
    fun total(range: DateRange): BigDecimal = sliceOf(range).fold(BigDecimal.ZERO) { acc, d -> acc + d.total }

    /** Σ discretionary spending over [range] (inclusive). */
    fun discretionary(range: DateRange): BigDecimal = sliceOf(range).fold(BigDecimal.ZERO) { acc, d -> acc + d.discretionary }

    /** How many days of history there are up to and including [asOf] (0 before the first activity). */
    fun knownDaysUpTo(asOf: LocalDate): Long = if (asOf.isBefore(firstDay)) 0 else DateRange(firstDay, asOf).days

    private fun sliceOf(range: DateRange) = byDay.subMap(range.from, range.to.plusDays(1)).values
}
