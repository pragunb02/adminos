package dev.adminos.api.domain.forecast.evaluation

import dev.adminos.api.domain.forecast.EmpiricalInterval
import dev.adminos.api.domain.forecast.Forecast
import dev.adminos.api.domain.forecast.ForecastInput
import dev.adminos.api.domain.forecast.MonthEndForecaster
import dev.adminos.api.domain.forecast.QuantileMethod
import dev.adminos.api.domain.forecast.RateEstimator
import dev.adminos.api.domain.forecast.RunRateModel
import dev.adminos.api.domain.forecast.SalaryOutlook
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/** One method's scores over every backtest forecast (AOS-714). */
@Serializable
data class MethodScore(
    val method: String,
    val forecasts: Int,
    /** Mean absolute percentage error of the month-end total, in percent. */
    val mape: Double,
    /** Mean absolute error in rupees. */
    val mae: Double,
    /** MAE ÷ the naive pace's MAE on the same forecasts (below 1 beats it; a MASE with the naive as the scale). */
    val relMae: Double,
    /** Share of actual month-ends inside the 80% range; null for methods without a range. */
    val coverage: Double? = null,
    /** Mean range width as a share of the actual month-end; null without a range. */
    val width: Double? = null,
    val mapeDay10: Double,
    val mapeDay20: Double
)

@Serializable
data class SalaryScore(
    /** Forecasts for personas with a regular salary where the next salary date was exactly right. */
    val dateAccuracy: Double,
    /** … and where it was within two days. */
    val dateWithin2Days: Double,
    /** Forecasts for the irregular persona where no salary was claimed (honest "unknown"). */
    val irregularHonesty: Double,
    /** Mean |forecast − actual| money left at the next salary, as a share of the salary, where it was forecast. */
    val leftErrorShare: Double,
    val forecasts: Int
)

@Serializable
data class BacktestReport(
    val histories: Int,
    val scores: List<MethodScore>,
    /** The shipped run-rate with each way of making the range. */
    val intervals: List<MethodScore>,
    val salary: SalaryScore,
    val notEnoughHistory: Int,
    /** The shipped engine's MAPE per persona, and seasonal naive's for comparison. */
    val byPersona: Map<String, List<Double>>
)

/**
 * Backtest on synthetic histories (AOS-714): for every history, month and forecast day (the 10th and 20th),
 * forecast with only what was known at the end of that day, then compare with the month's real total.
 * Baselines: the naive pace (spent so far × days in month ÷ days elapsed) and seasonal naive (last month's total).
 */
object ForecastBacktest {
    val FORECAST_DAYS = listOf(10, 20)
    /** The first month forecast: after three months of history. */
    val FIRST_MONTH: YearMonth = YearMonth.of(2027, 4)

    /** The shipped engine, and the variants it was chosen from. */
    val VARIANTS: Map<String, RunRateModel> = linkedMapOf(
        "engine (shipped)" to RunRateModel.DEFAULT,
        "median daily, pooled" to RunRateModel(estimator = RateEstimator.MEDIAN, splitWeekend = false),
        "median daily, weekday/weekend" to RunRateModel(estimator = RateEstimator.MEDIAN, splitWeekend = true),
        "trimmed mean, pooled" to RunRateModel(estimator = RateEstimator.TRIMMED_MEAN, splitWeekend = false),
        "trimmed mean 5%, weekday/weekend" to RunRateModel(estimator = RateEstimator.TRIMMED_MEAN, trim = 0.05),
        "weekly median, pooled" to RunRateModel(estimator = RateEstimator.WEEKLY_MEDIAN, splitWeekend = false),
        "trimmed mean 10%, weekday/weekend" to RunRateModel(estimator = RateEstimator.TRIMMED_MEAN, trim = 0.1),
        "plain mean, weekday/weekend" to RunRateModel(estimator = RateEstimator.TRIMMED_MEAN, trim = 0.0)
    )

    /** Ways of making the 80% range, all on the shipped run-rate. */
    val INTERVALS: Map<String, EmpiricalInterval> = linkedMapOf(
        "range (shipped)" to EmpiricalInterval.DEFAULT,
        "same day, interpolated" to EmpiricalInterval(neighbourDays = 0, quantiles = QuantileMethod.INTERPOLATED),
        "same day, conformal" to EmpiricalInterval(neighbourDays = 0, quantiles = QuantileMethod.CONFORMAL),
        "±3 days, interpolated" to EmpiricalInterval(neighbourDays = 3, quantiles = QuantileMethod.INTERPOLATED),
        "±3 days, conformal" to EmpiricalInterval(neighbourDays = 3, quantiles = QuantileMethod.CONFORMAL),
        "±5 days, conformal" to EmpiricalInterval(neighbourDays = 5, quantiles = QuantileMethod.CONFORMAL)
    )

    private data class Case(val h: SyntheticHistory, val today: LocalDate) {
        val month: YearMonth get() = YearMonth.from(today)
        val actual: BigDecimal get() = h.monthTotal(month)
    }

    private data class Point(val forecast: Double, val actual: Double, val low: Double? = null, val high: Double? = null, val day: Int)

    fun run(
        histories: List<SyntheticHistory> = SyntheticHistories.all(),
        variants: Map<String, RunRateModel> = VARIANTS,
        intervals: Map<String, EmpiricalInterval> = INTERVALS
    ): BacktestReport {
        val cases = histories.flatMap { h ->
            generateSequence(FIRST_MONTH) { it.plusMonths(1) }.takeWhile { !it.isAfter(YearMonth.from(h.end)) }
                .flatMap { m -> FORECAST_DAYS.map { Case(h, m.atDay(it)) } }.toList()
        }
        val naive = cases.map { c -> Point(naivePace(c), c.actual.toDouble(), day = c.today.dayOfMonth) }
        val seasonal = cases.map { c -> Point(c.h.monthTotal(c.month.minusMonths(1)).toDouble(), c.actual.toDouble(), day = c.today.dayOfMonth) }
        val naiveMae = naive.map { abs(it.forecast - it.actual) }.average()
        var notEnough = 0
        val scores = mutableListOf(score("naive pace (baseline)", naive, naiveMae), score("seasonal naive (baseline)", seasonal, naiveMae))
        for ((name, model) in variants) {
            val points = points(cases, MonthEndForecaster(model, EmpiricalInterval.DEFAULT.copy(model = model))) { notEnough++ }
            scores += score(name, points, naiveMae)
        }
        val ranges = intervals.map { (name, interval) -> score(name, points(cases, MonthEndForecaster(interval = interval)) {}, naiveMae) }
        val byPersona = cases.groupBy { it.h.persona.name }.mapValues { (_, cs) ->
            val engine = points(cs, MonthEndForecaster()) {}
            listOf(round(engine.map { abs(it.forecast - it.actual) / it.actual }.average() * 100),
                round(cs.map { c -> abs(c.h.monthTotal(c.month.minusMonths(1)).toDouble() - c.actual.toDouble()) / c.actual.toDouble() }.average() * 100))
        }
        return BacktestReport(histories.size, scores, ranges, salaryScore(cases), notEnough / variants.size.coerceAtLeast(1), byPersona)
    }

    private fun points(cases: List<Case>, forecaster: MonthEndForecaster, onRefused: () -> Unit): List<Point> = cases.mapNotNull { c ->
        when (val f = forecaster.forecast(input(c))) {
            is Forecast.NotEnoughHistory -> { onRefused(); null }
            is Forecast.Ready -> Point(f.monthEnd.total.toDouble(), c.actual.toDouble(), f.monthEnd.range?.low?.toDouble(), f.monthEnd.range?.high?.toDouble(), c.today.dayOfMonth)
        }
    }

    private fun input(c: Case) = ForecastInput(c.today, c.h.asOf(c.today), c.h.duesAfter(c.today, c.today.plusMonths(2)), c.h.incomesUpTo(c.today))

    private fun naivePace(c: Case): Double {
        val spent = c.h.spentBetween(c.month.atDay(1), c.today).toDouble()
        return spent * c.month.lengthOfMonth() / c.today.dayOfMonth
    }

    private fun score(method: String, points: List<Point>, naiveMae: Double): MethodScore {
        val ape = points.map { abs(it.forecast - it.actual) / it.actual }
        val ranged = points.filter { it.low != null && it.high != null }
        return MethodScore(
            method = method,
            forecasts = points.size,
            mape = round(ape.average() * 100),
            mae = round(points.map { abs(it.forecast - it.actual) }.average()),
            relMae = round(points.map { abs(it.forecast - it.actual) }.average() / naiveMae),
            coverage = ranged.takeIf { it.isNotEmpty() }?.let { r -> round(r.count { it.actual in (it.low ?: 0.0)..(it.high ?: 0.0) }.toDouble() / r.size) },
            width = ranged.takeIf { it.isNotEmpty() }?.let { r -> round(r.map { ((it.high ?: 0.0) - (it.low ?: 0.0)) / it.actual }.average()) },
            mapeDay10 = round(points.filter { it.day == 10 }.map { abs(it.forecast - it.actual) / it.actual }.average() * 100),
            mapeDay20 = round(points.filter { it.day == 20 }.map { abs(it.forecast - it.actual) / it.actual }.average() * 100)
        )
    }

    /** Salary date accuracy and money-left error, with the shipped engine. */
    private fun salaryScore(cases: List<Case>): SalaryScore {
        val forecaster = MonthEndForecaster()
        var regular = 0; var rightDate = 0; var near = 0; var irregular = 0; var honest = 0
        val leftErrors = mutableListOf<Double>()
        for (c in cases) {
            val f = forecaster.forecast(input(c)) as? Forecast.Ready ?: continue
            val next = c.h.salaries.firstOrNull { it.day.isAfter(c.today) }
            if (c.h.persona.payDay == PayDay.IRREGULAR) {
                irregular++
                if (f.salary is SalaryOutlook.Unknown) honest++
                continue
            }
            if (next == null) continue
            regular++
            val outlook = f.salary as? SalaryOutlook.Expected ?: continue
            if (outlook.untilSalary.salary.expectedOn == next.day) rightDate++
            if (abs(java.time.temporal.ChronoUnit.DAYS.between(outlook.untilSalary.salary.expectedOn, next.day)) <= 2) near++
            val last = c.h.salaries.last { !it.day.isAfter(c.today) }
            val actualLeft = last.amount - c.h.spentBetween(last.day, next.day.minusDays(1))
            leftErrors += (outlook.untilSalary.left - actualLeft).abs().divide(last.amount, 6, RoundingMode.HALF_UP).toDouble()
        }
        return SalaryScore(
            dateAccuracy = round(rightDate.toDouble() / regular.coerceAtLeast(1)),
            dateWithin2Days = round(near.toDouble() / regular.coerceAtLeast(1)),
            irregularHonesty = round(honest.toDouble() / irregular.coerceAtLeast(1)),
            leftErrorShare = round(leftErrors.average()),
            forecasts = regular + irregular
        )
    }

    private fun round(x: Double) = Math.round(x * 10_000) / 10_000.0
}
