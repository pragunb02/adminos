package dev.adminos.api.domain.forecast

import dev.adminos.api.domain.analytics.DateRange
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate

/** Weekdays and weekends are spent differently, so each gets its own rate when the history allows. */
enum class DayType {
    WEEKDAY, WEEKEND;

    companion object {
        fun of(day: LocalDate): DayType = if (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) WEEKEND else WEEKDAY
    }
}

/** How a daily rate is made robust to the odd big purchase (chosen by the AOS-714 backtest). */
enum class RateEstimator {
    /** Median of daily amounts: robust, but 0 for anyone who spends on fewer than half the days. */
    MEDIAN,
    /** Mean after dropping the top and bottom [RunRateModel.trim] share of days. */
    TRIMMED_MEAN,
    /** Median of weekly totals, per day: robust to spikes and to days without spending. */
    WEEKLY_MEDIAN
}

/** The fitted everyday-spending rate: rupees per day for each kind of day, from [lookback]. */
data class RunRateFit(val rates: Map<DayType, BigDecimal>, val lookback: DateRange, val split: Boolean, val estimator: RateEstimator) {
    init {
        require(rates.keys == DayType.entries.toSet()) { "A fit has a rate for every kind of day" }
        require(split || rates.values.distinct().size == 1) { "An unsplit fit has one rate" }
    }

    fun rateOf(type: DayType): BigDecimal = rates.getValue(type)
    fun rateOn(day: LocalDate): BigDecimal = rateOf(DayType.of(day))
}

/**
 * Fits a daily rate of discretionary spending (AOS-714): the last [lookbackDays] complete days that
 * are inside the user's history, at least [minLookbackDays] of them. Recurring payments are already
 * excluded from the history (they're forecast as known dues). Pure.
 */
data class RunRateModel(
    val lookbackDays: Int = 56,
    val minLookbackDays: Int = 28,
    val estimator: RateEstimator = RateEstimator.WEEKLY_MEDIAN,
    val splitWeekend: Boolean = true,
    val trim: Double = 0.1,
    /** A kind of day needs this many days in the lookback to get its own rate. */
    val minDaysPerType: Int = 8
) {
    init {
        require(minLookbackDays in 7..lookbackDays) { "minLookbackDays: between 7 and lookbackDays" }
        require(trim in 0.0..0.4) { "trim: between 0 and 0.4" }
        require(minDaysPerType >= 1) { "minDaysPerType: at least 1" }
    }

    /** The fit from the days up to and including [lastCompleteDay]; null when there are too few known days. */
    fun fit(history: SpendHistory, lastCompleteDay: LocalDate): RunRateFit? {
        val start = maxOf(lastCompleteDay.minusDays(lookbackDays - 1L), history.firstDay)
        if (start.isAfter(lastCompleteDay)) return null
        val window = DateRange(start, lastCompleteDay)
        if (window.days < minLookbackDays) return null
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(lastCompleteDay) }.toList()
        val split = splitWeekend && DayType.entries.all { t -> days.count { DayType.of(it) == t } >= minDaysPerType }
        val rates = if (split) {
            DayType.entries.associateWith { t -> rate(days.filter { DayType.of(it) == t }, history) }
        } else {
            val pooled = rate(days, history)
            DayType.entries.associateWith { pooled }
        }
        return RunRateFit(rates, window, split, estimator)
    }

    private fun rate(days: List<LocalDate>, history: SpendHistory): BigDecimal {
        val amounts = days.map { history.discretionaryOn(it) }
        return when (estimator) {
            RateEstimator.MEDIAN -> Stats.median(amounts)
            RateEstimator.TRIMMED_MEAN -> Stats.trimmedMean(amounts, trim)
            RateEstimator.WEEKLY_MEDIAN -> weeklyMedian(days, history)
        }.setScale(2, RoundingMode.HALF_UP)
    }

    /** Median over weeks (counted back from the newest day) of each week's total ÷ its number of [days]. */
    private fun weeklyMedian(days: List<LocalDate>, history: SpendHistory): BigDecimal {
        val newest = days.maxOrNull() ?: return BigDecimal.ZERO
        val weeks = days.groupBy { java.time.temporal.ChronoUnit.DAYS.between(it, newest) / 7 }.values
        return Stats.median(weeks.map { w -> w.fold(BigDecimal.ZERO) { acc, d -> acc + history.discretionaryOn(d) }.divide(BigDecimal(w.size), 6, RoundingMode.HALF_UP) })
    }

    companion object {
        /** The variant the backtest chose (see AOS-714 Verification). */
        val DEFAULT = RunRateModel()
    }
}

/** Small exact statistics over money. */
object Stats {
    fun median(values: List<BigDecimal>): BigDecimal {
        if (values.isEmpty()) return BigDecimal.ZERO
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]).divide(BigDecimal(2), 6, RoundingMode.HALF_UP)
    }

    /** The mean after dropping floor(n × [trim]) values from each end. */
    fun trimmedMean(values: List<BigDecimal>, trim: Double): BigDecimal {
        if (values.isEmpty()) return BigDecimal.ZERO
        val cut = kotlin.math.floor(values.size * trim).toInt()
        val kept = values.sorted().subList(cut, values.size - cut)
        return kept.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(kept.size), 6, RoundingMode.HALF_UP)
    }

    /** The [p]-quantile (0..1) with linear interpolation between order statistics (Hyndman–Fan type 7). */
    fun quantile(values: List<BigDecimal>, p: Double): BigDecimal {
        require(values.isNotEmpty()) { "A quantile needs values" }
        require(p in 0.0..1.0) { "p: between 0 and 1" }
        val s = values.sorted()
        val h = (s.size - 1) * p
        val lo = kotlin.math.floor(h).toInt()
        val hi = kotlin.math.ceil(h).toInt()
        val frac = BigDecimal(h - lo).setScale(6, RoundingMode.HALF_UP)
        return s[lo] + (s[hi] - s[lo]).multiply(frac)
    }
}
