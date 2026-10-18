package dev.adminos.api.domain.forecast

import dev.adminos.api.domain.analytics.DateRange
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/** Everything the forecast needs, already loaded (the forecaster does no I/O). */
data class ForecastInput(
    /** The user's own date (D-55). */
    val today: LocalDate,
    val history: SpendHistory,
    /** Known payments after today (bills, renewals, mandates), through at least the next salary. */
    val dues: List<KnownDue>,
    val incomes: List<IncomeCredit>,
    /** Monthly bills expected but not arrived yet (AOS-721), counted like known dues. */
    val projectedBills: List<ProjectedBill> = emptyList()
)

/** The month-end forecast: what's spent, plus what each component expects for the rest of the month. */
data class MonthEndForecast(
    val month: YearMonth,
    val today: LocalDate,
    /** This month through today, the same number as the console's "spent" (D-46). */
    val spentSoFar: BigDecimal,
    /** Tomorrow to the month's end; null on its last day. */
    val remaining: DateRange?,
    val parts: List<ComponentEstimate>,
    /** Null when there are too few past months to learn a range from ([rangeMonthsNeeded] says how many). */
    val range: ForecastRange?,
    val rangeMonthsNeeded: Int
) {
    val expected: BigDecimal get() = parts.fold(BigDecimal.ZERO) { acc, p -> acc + p.amount }
    val total: BigDecimal get() = (spentSoFar + expected).money()
}

/** What's left of the last salary when the next one comes, if spending goes as forecast. */
data class UntilSalary(
    val salary: DetectedSalary,
    /** Spent from the day the last salary came, through today. */
    val spentSince: BigDecimal,
    /** Tomorrow to the day before the next salary; null when it's due tomorrow or today. */
    val window: DateRange?,
    val parts: List<ComponentEstimate>,
    val range: ForecastRange?
) {
    val expected: BigDecimal get() = parts.fold(BigDecimal.ZERO) { acc, p -> acc + p.amount }
    /** Negative when the forecast runs out before the salary comes. */
    val left: BigDecimal get() = (salary.lastAmount - spentSince - expected).money()
    /** The range of [left]: a high spend leaves the low end. */
    val leftRange: Pair<BigDecimal, BigDecimal>? get() = range?.let { (salary.lastAmount - spentSince - it.high).money() to (salary.lastAmount - spentSince - it.low).money() }
}

sealed interface SalaryOutlook {
    data class Expected(val untilSalary: UntilSalary) : SalaryOutlook
    /** The salary's day has passed without it, so "money left" isn't computed. */
    data class Late(val salary: DetectedSalary) : SalaryOutlook
    data class Unknown(val reason: NoSalaryReason, val payer: String?, val lastSeen: LocalDate?) : SalaryOutlook
}

sealed interface Forecast {
    /** Below [neededDays] of history the forecast would be a guess, so it isn't made (AOS-S7.4). */
    data class NotEnoughHistory(val today: LocalDate, val historyDays: Long, val neededDays: Int, val readyOn: LocalDate?) : Forecast
    data class Ready(val monthEnd: MonthEndForecast, val salary: SalaryOutlook) : Forecast
}

/**
 * Month-end forecast and "money left until salary" (AOS-714, D-76), deterministic so every number
 * traces to its parts (D-67):
 * - **month-end** = spent so far + known dues after today (one component per source) + everyday spending at a
 *   robust daily rate × the remaining days, by weekday and weekend;
 * - **range** = the everyday part moved by the user's own past per-day errors on the same day of the month;
 * - **money left** = the last salary − spent since it − what the same components expect until the next one.
 * Pure: the caller loads [ForecastInput].
 */
class MonthEndForecaster(
    private val model: RunRateModel = RunRateModel.DEFAULT,
    private val interval: EmpiricalInterval = EmpiricalInterval.DEFAULT,
    private val salaries: SalaryDetector = SalaryDetector(),
    private val minHistoryDays: Int = MIN_HISTORY_DAYS
) {
    init {
        require(minHistoryDays >= model.minLookbackDays) { "minHistoryDays: at least the run-rate's minimum lookback" }
    }

    fun forecast(input: ForecastInput): Forecast {
        val today = input.today
        val historyDays = input.history.knownDaysUpTo(today)
        if (historyDays < minHistoryDays) {
            return Forecast.NotEnoughHistory(today, historyDays, minHistoryDays, readyOn = input.history.firstDay.plusDays(minHistoryDays - 1L).takeIf { historyDays > 0 })
        }
        val fit = model.fit(input.history, today) ?: error("history of $historyDays days must fit a rate")
        val components = componentsFor(input.dues.filter { it.date.isAfter(today) }, fit,
            projected = input.projectedBills.filter { it.expectedOn.isAfter(today) })
        val errors = interval.errors(input.history, today)
        return Forecast.Ready(monthEnd(input, components, errors), salaryOutlook(input, components, errors))
    }

    private fun monthEnd(input: ForecastInput, components: List<ForecastComponent>, errors: RateErrors): MonthEndForecast {
        val today = input.today
        val month = YearMonth.from(today)
        val remaining = EmpiricalInterval.remainingOf(today)
        val parts = components.map { it.estimate(remaining) }
        val spent = input.history.total(DateRange(month.atDay(1), today)).money()
        val point = spent + parts.sumOf { it.amount }
        return MonthEndForecast(
            month, today, spent, remaining, parts,
            range = interval.range(point, runRateOf(parts), remaining?.days?.toInt() ?: 0, errors),
            rangeMonthsNeeded = (interval.minMonths - errors.months).coerceAtLeast(0)
        )
    }

    private fun salaryOutlook(input: ForecastInput, components: List<ForecastComponent>, errors: RateErrors): SalaryOutlook =
        when (val found = salaries.detect(input.incomes, input.today)) {
            is SalaryFinding.NotFound -> SalaryOutlook.Unknown(found.reason, found.payer, found.lastSeen)
            is SalaryFinding.Found -> if (found.late) SalaryOutlook.Late(found.salary) else SalaryOutlook.Expected(untilSalary(input, found.salary, components, errors))
        }

    private fun untilSalary(input: ForecastInput, salary: DetectedSalary, components: List<ForecastComponent>, errors: RateErrors): UntilSalary {
        val today = input.today
        val lastDay = salary.expectedOn.minusDays(1)
        val window = if (lastDay.isAfter(today)) DateRange(today.plusDays(1), lastDay) else null
        val parts = components.map { it.estimate(window) }
        val spentSince = input.history.total(DateRange(salary.lastReceivedOn, today)).money()
        val expected = parts.sumOf { it.amount }
        return UntilSalary(salary, spentSince, window, parts, interval.range(expected, runRateOf(parts), window?.days?.toInt() ?: 0, errors))
    }

    private fun runRateOf(parts: List<ComponentEstimate>): BigDecimal = parts.filterIsInstance<ComponentEstimate.RunRate>().sumOf { it.amount }

    companion object {
        /** "Not enough history" below three months (AOS-S7.4). */
        const val MIN_HISTORY_DAYS = 90
    }
}
