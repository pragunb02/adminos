package dev.adminos.api.domain.forecast

import dev.adminos.api.domain.analytics.DateRange
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

/**
 * How wrong the everyday-spending rate was in the user's own past (AOS-714): for each of up to
 * [monthsBack] earlier months, the forecast this model would have made on the same day of that month
 * is compared with what was then actually spent. The errors are per remaining day, so months of
 * different lengths compare fairly.
 */
data class RateErrors(val perDay: List<BigDecimal>, val months: Int)

/** How the range's ends are read from the past errors. */
enum class QuantileMethod {
    /** Linear interpolation between order statistics (Hyndman–Fan type 7). */
    INTERPOLATED,
    /** Split-conformal ranks: the ⌈(n+1)(1−α/2)⌉-th error, clamped to the extremes for small n. */
    CONFORMAL
}

/** An interval around a forecast, and how many past months it was learnt from. */
data class ForecastRange(val low: BigDecimal, val high: BigDecimal, val level: Int, val months: Int) {
    init {
        require(low <= high) { "A range runs from low to high" }
    }
}

/**
 * Empirical intervals from past forecast errors (AOS-714; research report §11: quantiles of past errors
 * at the same day of the month). With fewer than [minMonths] past months the range is unknown, and the
 * forecast says so instead of guessing.
 */
data class EmpiricalInterval(
    val model: RunRateModel = RunRateModel.DEFAULT,
    val monthsBack: Int = 12,
    val minMonths: Int = 3,
    /** Nominal coverage: 0.8 aims for 8 in 10 month-ends inside the range. */
    val level: Double = 0.8,
    /** Also learn from forecasts made up to this many days either side of the same day, for more samples per month. */
    val neighbourDays: Int = 3,
    val quantiles: QuantileMethod = QuantileMethod.CONFORMAL
) {
    init {
        require(monthsBack >= minMonths) { "monthsBack: at least minMonths" }
        require(minMonths >= 2) { "minMonths: at least 2" }
        require(level > 0.0 && level < 1.0) { "level: between 0 and 1" }
        require(neighbourDays in 0..7) { "neighbourDays: 0 to 7" }
    }

    /** Past per-day errors (actual − forecast) of the everyday rate, made on the same day of earlier months as [today]. */
    fun errors(history: SpendHistory, today: LocalDate): RateErrors {
        val month = YearMonth.from(today)
        val months = (1..monthsBack).map { k -> month.minusMonths(k.toLong()) }
        val perMonth = months.map { m ->
            (-neighbourDays..neighbourDays).map { today.dayOfMonth + it }.filter { it in 1..m.lengthOfMonth() || it == today.dayOfMonth }
                .mapNotNull { day -> errorIn(history, m, day) }
        }
        return RateErrors(perMonth.flatten(), perMonth.count { it.isNotEmpty() })
    }

    private fun errorIn(history: SpendHistory, month: YearMonth, dayOfMonth: Int): BigDecimal? {
        val asOf = month.atDay(minOf(dayOfMonth, month.lengthOfMonth()))
        val remaining = remainingOf(asOf) ?: return null
        val fit = model.fit(history, asOf) ?: return null
        val predicted = RunRateComponent(fit).estimate(remaining).amount
        val actual = history.discretionary(remaining)
        return (actual - predicted).divide(BigDecimal(remaining.days), 6, RoundingMode.HALF_UP)
    }

    /**
     * The range around [point] for a window of [days] future days whose everyday part is [runRate]:
     * the everyday part moves by the past per-day error quantiles × [days], never below nothing.
     * Null when there are too few past months.
     */
    fun range(point: BigDecimal, runRate: BigDecimal, days: Int, errors: RateErrors): ForecastRange? {
        if (errors.months < minMonths) return null
        val pct = (level * 100).toInt()
        if (days == 0) return ForecastRange(point.money(), point.money(), pct, errors.months)
        val fixed = point - runRate
        val tail = (1 - level) / 2
        val low = (runRate + quantile(errors.perDay, tail).multiply(BigDecimal(days))).max(BigDecimal.ZERO)
        val high = (runRate + quantile(errors.perDay, 1 - tail).multiply(BigDecimal(days))).max(BigDecimal.ZERO)
        return ForecastRange((fixed + low).money(), (fixed + high).money(), pct, errors.months)
    }

    private fun quantile(values: List<BigDecimal>, p: Double): BigDecimal = when (quantiles) {
        QuantileMethod.INTERPOLATED -> Stats.quantile(values, p)
        QuantileMethod.CONFORMAL -> {
            val s = values.sorted()
            val rank = if (p >= 0.5) kotlin.math.ceil((s.size + 1) * p).toInt() else kotlin.math.floor((s.size + 1) * p).toInt()
            s[(rank - 1).coerceIn(0, s.size - 1)]
        }
    }

    companion object {
        /** The days after [asOf] to its month's end; null on the last day of the month. */
        fun remainingOf(asOf: LocalDate): DateRange? {
            val end = YearMonth.from(asOf).atEndOfMonth()
            return if (asOf == end) null else DateRange(asOf.plusDays(1), end)
        }

        val DEFAULT = EmpiricalInterval()
    }
}
