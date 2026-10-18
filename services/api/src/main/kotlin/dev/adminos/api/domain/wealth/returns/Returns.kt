package dev.adminos.api.domain.wealth.returns

import dev.adminos.api.domain.wealth.InvestmentTransaction
import dev.adminos.api.domain.wealth.InvestmentTxnType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * A dated cash flow from the investor's side (Excel's convention): negative = money put in, positive = money taken
 * out (a redemption, a dividend paid out, or today's value as if sold).
 */
data class CashFlow(val date: LocalDate, val amount: BigDecimal)

/** Why a money-weighted return can't be given. */
enum class NotDefinedReason(val text: String) {
    TOO_FEW_FLOWS("Not enough transactions to compute a return"),
    SAME_DAY("All the money moved on one day, so there is no period to annualise"),
    NO_SIGN_CHANGE("The money only went one way, so no rate fits"),
    MULTIPLE_RATES("Money went in and out more than once and several rates fit; none is shown"),
    OUT_OF_RANGE("No rate between −99.99% and +1,000% a year fits");
}

sealed interface XirrResult {
    /** An annual rate (0.1234 = 12.34% a year) that solves NPV = 0 to within [Xirr.TOLERANCE]. */
    data class Rate(val rate: BigDecimal) : XirrResult
    data class NotDefined(val reason: NotDefinedReason) : XirrResult
}

/**
 * XIRR with a bracketed solver (AOS-812, D-79, WEALTH-RESEARCH §3.3): Actual/365, rates searched in
 * [−99.99%, +1,000%]. The bracket is scanned first; no sign change → "not defined", more than one → "several rates fit,
 * none shown"; exactly one → bisection to [TOLERANCE]. Newton's method (Excel's) is not used: it can diverge or land
 * on the wrong root. The answer is never an unconverged number.
 */
object Xirr {
    val LOW: BigDecimal = BigDecimal("-0.9999")
    val HIGH: BigDecimal = BigDecimal("10")
    /** The bracket is narrowed until it is this wide (a rate to 10 decimal places). */
    val TOLERANCE: BigDecimal = BigDecimal("1E-11")
    private const val SCAN_POINTS = 60
    private const val MAX_STEPS = 200
    private val DAYS = BigDecimal(365)

    fun solve(flows: List<CashFlow>): XirrResult {
        val nonZero = flows.filter { it.amount.signum() != 0 }
        if (nonZero.size < 2) return XirrResult.NotDefined(NotDefinedReason.TOO_FEW_FLOWS)
        val first = nonZero.minOf { it.date }
        if (nonZero.all { it.date == first }) return XirrResult.NotDefined(NotDefinedReason.SAME_DAY)
        if (nonZero.none { it.amount.signum() > 0 } || nonZero.none { it.amount.signum() < 0 }) return XirrResult.NotDefined(NotDefinedReason.NO_SIGN_CHANGE)
        val days = nonZero.map { it.amount to ChronoUnit.DAYS.between(first, it.date).toInt() }

        // Scan the bracket on a grid that is fine near 0 (where real returns are) and coarse towards +1,000%.
        val grid = scanGrid()
        val values = grid.map { npvDays(days, it) }
        val changes = (1 until grid.size).filter { values[it - 1].signum() != 0 && values[it - 1].signum() != values[it].signum() }
        val exact = values.indexOfFirst { it.signum() == 0 }
        if (exact >= 0 && changes.isEmpty()) return XirrResult.Rate(grid[exact].setScale(10, RoundingMode.HALF_EVEN))
        return when (changes.size) {
            0 -> XirrResult.NotDefined(NotDefinedReason.OUT_OF_RANGE)
            1 -> XirrResult.Rate(bisect(days, grid[changes[0] - 1], grid[changes[0]]))
            else -> XirrResult.NotDefined(NotDefinedReason.MULTIPLE_RATES)
        }
    }

    /** NPV of the flows at [rate]: Σ amount × (1 + rate)^(−days/365). */
    fun npv(flows: List<CashFlow>, rate: BigDecimal): BigDecimal {
        val first = flows.minOf { it.date }
        return npvDays(flows.map { it.amount to ChronoUnit.DAYS.between(first, it.date).toInt() }, rate)
    }

    /**
     * (1 + r)^(−d/365) = vᵈ with v = e^(−ln(1 + r)/365), so one exp per rate and an exact integer power per flow:
     * fast and exact at [BigMath.MC].
     */
    private fun npvDays(flows: List<Pair<BigDecimal, Int>>, rate: BigDecimal): BigDecimal {
        val v = BigMath.exp(BigMath.ln(BigDecimal.ONE.add(rate, BigMath.MC)).negate().divide(DAYS, BigMath.MC))
        return flows.fold(BigDecimal.ZERO) { acc, (amount, d) -> acc.add(amount.multiply(v.pow(d, BigMath.MC), BigMath.MC), BigMath.MC) }
    }

    private fun bisect(days: List<Pair<BigDecimal, Int>>, from: BigDecimal, to: BigDecimal): BigDecimal {
        var lo = from
        var hi = to
        val loSign = npvDays(days, lo).signum()
        var steps = 0
        while (hi.subtract(lo) > TOLERANCE && steps++ < MAX_STEPS) {
            val mid = lo.add(hi).divide(BigDecimal(2), BigMath.MC)
            val s = npvDays(days, mid).signum()
            if (s == 0) return mid.setScale(10, RoundingMode.HALF_EVEN)
            if (s == loSign) lo = mid else hi = mid
        }
        return lo.add(hi).divide(BigDecimal(2), BigMath.MC).setScale(10, RoundingMode.HALF_EVEN)
    }

    /** −99.99% … +1,000%: dense between −50% and +100%, then wider steps. */
    private fun scanGrid(): List<BigDecimal> {
        val points = mutableListOf(LOW, BigDecimal("-0.99"), BigDecimal("-0.9"), BigDecimal("-0.75"))
        val dense = SCAN_POINTS
        for (i in 0..dense) points += BigDecimal("-0.5").add(BigDecimal("1.5").multiply(BigDecimal(i)).divide(BigDecimal(dense), BigMath.MC))
        points += listOf(BigDecimal("1.5"), BigDecimal("2"), BigDecimal("3"), BigDecimal("5"), BigDecimal("7.5"), HIGH)
        return points
    }
}

/** How a return is shown (SEBI/AMFI convention): under a year as an absolute return, from a year as an annual rate. */
enum class ReturnBasis { ABSOLUTE, ANNUALISED, NONE }

/**
 * A holding's or portfolio's return as shown (AOS-812). [xirr] is always computed when defined; [shown] is what the
 * screen leads with: [absolute] (gain ÷ money put in) when the first investment is under a year old, else the XIRR.
 */
data class ReturnFigure(val xirr: XirrResult, val absolute: BigDecimal?, val days: Long, val basis: ReturnBasis) {
    val shown: BigDecimal? get() = when (basis) {
        ReturnBasis.ABSOLUTE -> absolute
        ReturnBasis.ANNUALISED -> (xirr as? XirrResult.Rate)?.rate
        ReturnBasis.NONE -> null
    }
    val reason: String? get() = (xirr as? XirrResult.NotDefined)?.reason?.text?.takeIf { basis == ReturnBasis.NONE }
}

object Returns {
    const val YEAR_DAYS = 365L

    /**
     * Investor cash flows of ledger rows in rupees (WEALTH-RESEARCH §3.3): purchases, stamp duty, STT and TDS out;
     * redemptions and dividends paid out in (the gross dividend in and its TDS out, so the net is what arrived);
     * reinvestments, bonuses, splits, mergers and transfers move no cash. A reversal returns money when it removes units.
     */
    fun cashFlows(rows: List<InvestmentTransaction>): List<CashFlow> = rows.mapNotNull { r ->
        val amount = r.amountInr ?: return@mapNotNull null
        if (amount.signum() == 0) return@mapNotNull null
        val sign = if (r.type == InvestmentTxnType.REVERSAL) -(r.units?.signum ?: 0) else r.type.cashFlow
        if (sign == 0) null else CashFlow(r.tradeDate, if (sign > 0) amount else amount.negate())
    }

    /** The return of [flows] plus [value] held on [asOf] (counted as if taken out that day). */
    fun of(flows: List<CashFlow>, value: BigDecimal, asOf: LocalDate): ReturnFigure {
        val all = flows + listOfNotNull(CashFlow(asOf, value).takeIf { value.signum() != 0 })
        val xirr = Xirr.solve(all)
        val start = flows.minOfOrNull { it.date } ?: asOf
        val days = ChronoUnit.DAYS.between(start, asOf)
        val putIn = flows.filter { it.amount.signum() < 0 }.fold(BigDecimal.ZERO) { a, f -> a - f.amount }
        val net = all.fold(BigDecimal.ZERO) { a, f -> a + f.amount }
        val absolute = if (putIn.signum() > 0) net.divide(putIn, BigMath.MC).setScale(10, RoundingMode.HALF_EVEN) else null
        val basis = when {
            days < YEAR_DAYS && absolute != null -> ReturnBasis.ABSOLUTE
            xirr is XirrResult.Rate -> ReturnBasis.ANNUALISED
            else -> ReturnBasis.NONE
        }
        return ReturnFigure(xirr, absolute, days, basis)
    }
}

/**
 * Modified Dietz (time-weighted approximation for benchmark comparison, AOS-812): R = (EMV − BMV − ΣC) ÷ (BMV + Σ wᵢCᵢ),
 * wᵢ = (days from Cᵢ to the end) ÷ (days in the period). Here a contribution Cᵢ is positive money *into* the portfolio
 * (the opposite sign to [CashFlow]). Null when the denominator is not positive (nothing was invested).
 */
object ModifiedDietz {
    fun of(start: LocalDate, end: LocalDate, startValue: BigDecimal, endValue: BigDecimal, contributions: List<CashFlow>): BigDecimal? {
        require(end.isAfter(start)) { "the period must have at least one day" }
        require(contributions.all { !it.date.isBefore(start) && !it.date.isAfter(end) }) { "contributions must fall in the period" }
        val days = BigDecimal(ChronoUnit.DAYS.between(start, end))
        val sum = contributions.fold(BigDecimal.ZERO) { a, c -> a + c.amount }
        val weighted = contributions.fold(BigDecimal.ZERO) { a, c ->
            a + c.amount.multiply(BigDecimal(ChronoUnit.DAYS.between(c.date, end))).divide(days, BigMath.MC)
        }
        val denominator = startValue + weighted
        if (denominator.signum() <= 0) return null
        return (endValue - startValue - sum).divide(denominator, BigMath.MC).setScale(10, RoundingMode.HALF_EVEN)
    }

    /** Links sub-period returns: Π(1 + rᵢ) − 1. */
    fun chain(returns: List<BigDecimal>): BigDecimal =
        returns.fold(BigDecimal.ONE) { a, r -> a.multiply(BigDecimal.ONE + r, BigMath.MC) }.subtract(BigDecimal.ONE).setScale(10, RoundingMode.HALF_EVEN)
}
