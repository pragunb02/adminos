package dev.adminos.api.domain.forecast.evaluation

import dev.adminos.api.domain.forecast.RunRateModel
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AOS-714 (D-61 style): the shipped forecast may not score worse than the checked-in baseline on the synthetic
 * backtest, must beat both simple baselines, and its 80% range must cover 70–90% of month-ends. Raising the floor
 * is deliberate: `./gradlew -q forecastReport -PwriteBaseline`, committed with the change that moved it.
 */
class ForecastBacktestTest {

    private val report = ForecastBacktest.run(variants = mapOf("engine (shipped)" to RunRateModel.DEFAULT), intervals = emptyMap())
    private val engine = report.engine()
    private val baseline = ForecastBaseline.load()

    @Test
    fun `the forecast scores no worse than the baseline`() {
        assertEquals(baseline.forecasts, engine.forecasts, "The backtest changed size: re-record with ./gradlew -q forecastReport -PwriteBaseline")
        val drops = buildList {
            if (engine.mape > baseline.mape + TOLERANCE) add("MAPE rose from ${baseline.mape} to ${engine.mape}")
            if (engine.relMae > baseline.relMae + TOLERANCE) add("relative MAE rose from ${baseline.relMae} to ${engine.relMae}")
            if (report.salary.dateAccuracy < baseline.salaryDateAccuracy - TOLERANCE) add("salary date accuracy fell from ${baseline.salaryDateAccuracy} to ${report.salary.dateAccuracy}")
            if (report.salary.irregularHonesty < baseline.irregularHonesty - TOLERANCE) add("irregular-income honesty fell from ${baseline.irregularHonesty} to ${report.salary.irregularHonesty}")
        }
        if (drops.isNotEmpty()) fail("The forecast got worse on the backtest:\n" + drops.joinToString("\n") { "  - $it" } +
            "\nRun ./gradlew -q forecastReport to see the table. If intended, re-record with -PwriteBaseline and say why in the ticket.")
    }

    @Test
    fun `the forecast beats the naive pace and seasonal naive`() {
        val naive = report.scores.first { it.method.startsWith("naive pace") }
        val seasonal = report.scores.first { it.method.startsWith("seasonal naive") }
        assertTrue(engine.relMae < 1.0, "relative MAE vs the naive pace is ${engine.relMae}")
        assertTrue(engine.mae < naive.mae && engine.mae < seasonal.mae, "MAE ${engine.mae} vs naive ${naive.mae} and seasonal ${seasonal.mae}")
        assertTrue(engine.mape < seasonal.mape, "MAPE ${engine.mape} vs seasonal naive ${seasonal.mape}")
    }

    @Test
    fun `the 80 percent range covers between 70 and 90 percent of month-ends`() {
        val coverage = engine.coverage ?: fail("the shipped engine made no ranges")
        assertTrue(abs(coverage - 0.8) <= 0.10, "coverage $coverage")
    }

    @Test
    fun `a regular salary is placed within two days and irregular income is not called a salary`() {
        assertTrue(report.salary.dateWithin2Days >= 0.95, "within two days ${report.salary.dateWithin2Days}")
        assertTrue(report.salary.irregularHonesty >= 0.95, "irregular honesty ${report.salary.irregularHonesty}")
    }

    @Test
    fun `synthetic histories are deterministic, span a leap February and know nothing of the future`() {
        val p = SyntheticHistories.PERSONAS.first()
        val a = SyntheticHistories.generate(p, 7)
        val b = SyntheticHistories.generate(p, 7)
        assertEquals(a.monthTotal(YearMonth.of(2028, 2)), b.monthTotal(YearMonth.of(2028, 2)))
        assertTrue(YearMonth.of(2028, 2).isLeapYear && !SyntheticHistories.END.isBefore(LocalDate.of(2028, 2, 29)))
        val today = LocalDate.of(2027, 6, 10)
        assertEquals(java.math.BigDecimal.ZERO, a.asOf(today).totalOn(today.plusDays(1)))
        assertTrue(a.incomesUpTo(today).all { !it.day.isAfter(today) })
    }

    @Test
    fun `projected bills make the month-end no worse when a bill arrives a week before it is due`() {
        val late = LateBillsBacktest.run()
        println("Late bills (AOS-721): month-end MAPE ${late.mapeWithout}% without projection, ${late.mapeWith}% with (on the 10th ${late.mapeDay10Without}% vs ${late.mapeDay10With}%), ${late.forecasts} forecasts")
        assertEquals(engine.forecasts, late.forecasts)
        assertTrue(late.mapeWith <= late.mapeWithout, "MAPE ${late.mapeWith} with projected bills vs ${late.mapeWithout} without")
    }

    @Test
    fun `a missing baseline fails clearly`() {
        assertFailsWith<IllegalStateException> { ForecastBaseline.load("/forecast/missing.json") }
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
