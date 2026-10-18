package dev.adminos.api.domain.forecast.evaluation

import dev.adminos.api.domain.financial.BillType
import dev.adminos.api.domain.forecast.Forecast
import dev.adminos.api.domain.forecast.ForecastInput
import dev.adminos.api.domain.forecast.MonthEndForecaster
import dev.adminos.api.domain.forecast.MonthlyBillProjector
import dev.adminos.api.domain.forecast.PastBill
import dev.adminos.api.domain.forecast.ProjectedBill
import kotlinx.serialization.Serializable
import java.time.YearMonth
import kotlin.math.abs

/** Month-end MAPE on the late-bills variant, without and with projected bills (AOS-721). */
@Serializable
data class LateBillsScore(val forecasts: Int, val mapeWithout: Double, val mapeWith: Double, val mapeDay10Without: Double, val mapeDay10With: Double)

/**
 * The main backtest knows every due ahead of time (an oracle), so it can't see projected bills. This variant
 * makes the synthetic electricity bill ([BILLER], the 20th, seasonal) arrive only [LEAD_DAYS] before its due
 * date, as real bills do, and gives its earlier months to [MonthlyBillProjector] as past bills. It compares the
 * shipped engine without and with the projection on the same forecasts as [ForecastBacktest].
 */
object LateBillsBacktest {
    const val BILLER = "Tata Power"
    const val LEAD_DAYS = 7L

    fun run(histories: List<SyntheticHistory> = SyntheticHistories.all()): LateBillsScore {
        val forecaster = MonthEndForecaster()
        val projector = MonthlyBillProjector()
        val without = mutableListOf<Pair<Int, Double>>()
        val with = mutableListOf<Pair<Int, Double>>()
        for (h in histories) {
            val bill = h.scheduled(BILLER)
            val months = generateSequence(ForecastBacktest.FIRST_MONTH) { it.plusMonths(1) }.takeWhile { !it.isAfter(YearMonth.from(h.end)) }
            for (today in months.flatMap { m -> ForecastBacktest.FORECAST_DAYS.map { m.atDay(it) } }) {
                val actual = h.monthTotal(YearMonth.from(today)).toDouble()
                // A bill is known once it arrives, LEAD_DAYS before it's due.
                val dues = h.duesAfter(today, today.plusMonths(2)).filter { it.name != BILLER || !it.date.minusDays(LEAD_DAYS).isAfter(today) }
                val arrived = generateSequence(YearMonth.from(h.start)) { it.plusMonths(1) }.map { SyntheticHistory.dateIn(bill, it) }
                    .takeWhile { !it.minusDays(LEAD_DAYS).isAfter(today) }
                    .map { PastBill(BillType.ELECTRICITY, BILLER, null, it, bill.amount(YearMonth.from(it))) }.toList()
                val base = ForecastInput(today, h.asOf(today), dues, h.incomesUpTo(today))
                val projected: List<ProjectedBill> = projector.project(arrived.filter { !it.dueDate.isBefore(YearMonth.from(today).minusMonths(MonthlyBillProjector.HISTORY_MONTHS).atDay(1)) }, today)
                val a = forecaster.forecast(base) as? Forecast.Ready ?: continue
                val b = forecaster.forecast(base.copy(projectedBills = projected)) as? Forecast.Ready ?: continue
                without += today.dayOfMonth to abs(a.monthEnd.total.toDouble() - actual) / actual
                with += today.dayOfMonth to abs(b.monthEnd.total.toDouble() - actual) / actual
            }
        }
        return LateBillsScore(without.size, mape(without), mape(with), mape(without.filter { it.first == 10 }), mape(with.filter { it.first == 10 }))
    }

    private fun mape(errors: List<Pair<Int, Double>>) = Math.round(errors.map { it.second }.average() * 100 * 10_000) / 10_000.0
}
