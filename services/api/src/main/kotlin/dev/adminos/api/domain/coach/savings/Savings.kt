package dev.adminos.api.domain.coach.savings

import dev.adminos.api.domain.analytics.AnalyticsService
import dev.adminos.api.domain.analytics.Bucket
import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.coach.leaks.LeakService
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/** One month: income (D-39: income nature only; transfers and refunds are neither), spent (spend − refunds), and the rate. */
data class SavingsMonth(val month: YearMonth, val income: BigDecimal, val spent: BigDecimal, val partial: Boolean) {
    val saved: BigDecimal get() = income - spent
    /** (income − spent) ÷ income, 4 decimals; null when there was no income (a rate of nothing means nothing). Can be negative. */
    val rate: BigDecimal? get() = if (income.signum() <= 0) null else saved.divide(income, 4, RoundingMode.HALF_UP)
}

/** The editable assumptions of "what this could become", shown with the result. */
data class Assumptions(val annualReturnPct: BigDecimal, val inflationPct: BigDecimal) {
    init {
        require(annualReturnPct >= BigDecimal.ZERO && annualReturnPct <= BigDecimal(30)) { "annualReturnPct: 0..30" }
        require(inflationPct >= BigDecimal.ZERO && inflationPct <= BigDecimal(20)) { "inflationPct: 0..20" }
    }

    companion object {
        /** Long-run Indian equity SIP assumption and CPI inflation, both stated next to the result and editable. */
        val DEFAULT = Assumptions(BigDecimal(12), BigDecimal(6))
    }
}

data class Horizon(val years: Int, val contributed: BigDecimal, val futureValue: BigDecimal, val inTodaysMoney: BigDecimal)

/** A month's leak spend invested monthly instead, as a SIP, over 5 and 10 years. */
data class Projection(val month: YearMonth, val monthlyAmount: BigDecimal, val assumptions: Assumptions, val horizons: List<Horizon>)

data class SavingsReport(
    val months: List<SavingsMonth>,
    /** This month if it has income, else the latest month that does; null with no income at all (too little data). */
    val headline: SavingsMonth?,
    /** The month before the headline month, for the trend; null if it had no income. */
    val previous: SavingsMonth?,
    val projection: Projection?
)

/**
 * SIP maths (AOS-908), the formula shown under "how we got this":
 *   FV = P × ((1 + r)ⁿ − 1) ÷ r × (1 + r), with r = annual return ÷ 12, n = months (paid at the start of each month);
 *   r = 0 → FV = P × n.  In today's money: FV ÷ (1 + inflation)^years.
 */
object Sip {
    private val MC = MathContext(34, RoundingMode.HALF_EVEN)
    const val FORMULA = "FV = P × ((1 + r)^n − 1) ÷ r × (1 + r); r = annual return ÷ 12; n = 12 × years; in today's money = FV ÷ (1 + inflation)^years"

    fun futureValue(monthly: BigDecimal, annualReturnPct: BigDecimal, years: Int): BigDecimal {
        require(years in 1..50) { "years: 1..50" }
        val n = years * 12
        val r = annualReturnPct.divide(BigDecimal(1200), MC)
        if (r.signum() == 0) return monthly.multiply(BigDecimal(n)).setScale(0, RoundingMode.HALF_UP)
        val growth = BigDecimal.ONE.add(r, MC).pow(n, MC)
        return monthly.multiply(growth.subtract(BigDecimal.ONE, MC), MC).divide(r, MC).multiply(BigDecimal.ONE.add(r, MC), MC).setScale(0, RoundingMode.HALF_UP)
    }

    fun inTodaysMoney(value: BigDecimal, inflationPct: BigDecimal, years: Int): BigDecimal =
        value.divide(BigDecimal.ONE.add(inflationPct.divide(BigDecimal(100), MC), MC).pow(years, MC), MC).setScale(0, RoundingMode.HALF_UP)

    fun horizon(monthly: BigDecimal, a: Assumptions, years: Int): Horizon {
        val fv = futureValue(monthly, a.annualReturnPct, years)
        return Horizon(years, monthly.multiply(BigDecimal(years * 12)).setScale(0, RoundingMode.HALF_UP), fv, inTodaysMoney(fv, a.inflationPct, years))
    }

    val YEARS = listOf(5, 10)
}

/**
 * The savings rate (AOS-908, D-80): (income − spent) ÷ income per month, the Home headline, from the console's
 * own analytics (D-45), so it agrees with every other number. "What this could become": the last full
 * month's leak spend (AOS-902) as a monthly SIP.
 */
class SavingsService(private val analytics: AnalyticsService, private val leaks: LeakService, private val clock: Clock = Clock.systemUTC()) {

    suspend fun report(userId: UUID, zone: ZoneId, months: Int, assumptions: Assumptions): SavingsReport {
        require(months in MIN_MONTHS..MAX_MONTHS) { "months: $MIN_MONTHS..$MAX_MONTHS" }
        val today = LocalDate.now(clock.withZone(zone))
        val thisMonth = YearMonth.from(today)
        val first = thisMonth.minusMonths(months - 1L)
        val points = analytics.series(userId, DateRange(first.atDay(1), today), Bucket.MONTH, zone)
        val list = points.map { SavingsMonth(YearMonth.from(it.start), it.money.received, it.money.spent, partial = YearMonth.from(it.start) == thisMonth) }
        val headline = list.lastOrNull { it.rate != null }
        val previous = headline?.let { h -> list.firstOrNull { it.month == h.month.minusMonths(1) } }?.takeIf { it.rate != null }
        return SavingsReport(list, headline, previous, projection(userId, zone, thisMonth.minusMonths(1), assumptions))
    }

    private suspend fun projection(userId: UUID, zone: ZoneId, month: YearMonth, a: Assumptions): Projection? {
        val leak = leaks.report(userId, DateRange(month.atDay(1), month.atEndOfMonth()), zone).total.spent
        if (leak.signum() <= 0) return null
        return Projection(month, leak, a, Sip.YEARS.map { Sip.horizon(leak, a, it) })
    }

    companion object {
        const val MIN_MONTHS = 3
        const val MAX_MONTHS = 24
        const val DEFAULT_MONTHS = 12
    }
}
