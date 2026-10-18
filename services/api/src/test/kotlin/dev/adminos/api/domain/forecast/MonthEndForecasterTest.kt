package dev.adminos.api.domain.forecast

import dev.adminos.api.domain.analytics.DateRange
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-714: the forecast adds up from its parts, says when it can't, and handles month and salary edges. */
class MonthEndForecasterTest {

    private fun d(s: String) = LocalDate.parse(s)
    private fun inr(s: String) = BigDecimal(s)

    /** [from]..[to]: 300 a weekday, 600 a weekend day of everyday spending, and rent of 18,000 on each 5th (recurring). */
    private fun history(from: String, to: String): SpendHistory {
        val days = generateSequence(d(from)) { it.plusDays(1) }.takeWhile { !it.isAfter(d(to)) }.map { day ->
            SpendDay(day, inr(if (DayType.of(day) == DayType.WEEKEND) "600" else "300"), if (day.dayOfMonth == 5) inr("18000") else BigDecimal.ZERO)
        }.toList()
        return SpendHistory(days, d(from))
    }

    private val salaries = listOf("2026-06-01", "2026-07-01", "2026-08-01", "2026-09-01", "2026-10-01").map { IncomeCredit(d(it), "ACME", inr("85000")) }

    private fun ready(input: ForecastInput, forecaster: MonthEndForecaster = MonthEndForecaster()) = assertIs<Forecast.Ready>(forecaster.forecast(input))

    @Test
    fun `below 90 days of history it says so and when it will be ready`() {
        val f = assertIs<Forecast.NotEnoughHistory>(MonthEndForecaster().forecast(ForecastInput(d("2026-10-17"), history("2026-08-01", "2026-10-17"), emptyList(), emptyList())))
        assertEquals(78, f.historyDays)
        assertEquals(90, f.neededDays)
        assertEquals(d("2026-10-29"), f.readyOn)
        val none = assertIs<Forecast.NotEnoughHistory>(MonthEndForecaster().forecast(ForecastInput(d("2026-10-17"), SpendHistory(emptyList(), d("2026-10-18")), emptyList(), emptyList())))
        assertNull(none.readyOn)
    }

    @Test
    fun `the month-end is spent so far plus each source plus everyday spending, and it adds up`() {
        val today = d("2026-10-20")
        val dues = listOf(
            KnownDue(DueSource.BILL, "Tata Power", d("2026-10-25"), inr("2350.50")),
            KnownDue(DueSource.RENEWAL, "Netflix", d("2026-10-28"), inr("649")),
            KnownDue(DueSource.MANDATE, "SIP", d("2026-10-20"), inr("5000")), // today: already in "spent so far"
            KnownDue(DueSource.BILL, "Rent", d("2026-11-05"), inr("18000"))  // next month
        )
        val f = ready(ForecastInput(today, history("2026-05-01", "2026-10-20"), dues, emptyList())).monthEnd
        // 1–20 Oct: 14 weekdays × 300 + 6 weekend days × 600 + rent 18,000.
        assertEquals(inr("25800.00"), f.spentSoFar)
        assertEquals(DateRange(d("2026-10-21"), d("2026-10-31")), f.remaining)
        val bySource = f.parts.filterIsInstance<ComponentEstimate.Dues>().associate { it.source to it.amount }
        assertEquals(inr("2350.50"), bySource[DueSource.BILL])
        assertEquals(inr("649"), bySource[DueSource.RENEWAL])
        assertEquals(BigDecimal.ZERO, bySource[DueSource.MANDATE])
        val everyday = f.parts.filterIsInstance<ComponentEstimate.RunRate>().single()
        assertEquals(inr("300.00"), everyday.fit.rateOf(DayType.WEEKDAY))
        assertEquals(inr("600.00"), everyday.fit.rateOf(DayType.WEEKEND))
        assertEquals(inr("4200.00"), everyday.amount) // 8 weekdays × 300 + 3 weekend days × 600
        assertEquals(inr("32999.50"), f.total)
        assertEquals(f.total, (f.spentSoFar + f.parts.sumOf { it.amount }).setScale(2))
    }

    @Test
    fun `a perfectly regular history gives a tight range around the forecast`() {
        val f = ready(ForecastInput(d("2026-10-20"), history("2026-01-01", "2026-10-20"), emptyList(), emptyList())).monthEnd
        val range = assertNotNull(f.range)
        assertEquals(80, range.level)
        assertTrue(range.months >= 3)
        assertTrue(range.low <= f.total && f.total <= range.high)
        assertTrue(range.high - range.low < inr("1000"), "width ${range.high - range.low}")
        assertEquals(0, f.rangeMonthsNeeded)
    }

    @Test
    fun `with under three past months there is no range and it says how many more are needed`() {
        val f = ready(ForecastInput(d("2026-10-20"), history("2026-07-15", "2026-10-20"), emptyList(), emptyList())).monthEnd
        assertNull(f.range)
        assertTrue(f.rangeMonthsNeeded in 1..3)
    }

    @Test
    fun `on the last day of the month nothing is left to forecast`() {
        val f = ready(ForecastInput(d("2026-10-31"), history("2026-01-01", "2026-10-31"), emptyList(), emptyList())).monthEnd
        assertNull(f.remaining)
        assertTrue(f.parts.all { it.amount.signum() == 0 })
        assertEquals(f.spentSoFar, f.total)
        val range = assertNotNull(f.range)
        assertEquals(f.total, range.low)
        assertEquals(f.total, range.high)
    }

    @Test
    fun `a leap February counts its 29th day`() {
        val f = ready(ForecastInput(d("2028-02-20"), history("2027-09-01", "2028-02-20"), emptyList(), emptyList())).monthEnd
        assertEquals(DateRange(d("2028-02-21"), d("2028-02-29")), f.remaining)
    }

    @Test
    fun `money left until salary is the last salary minus what was spent since and what is expected until the next`() {
        val today = d("2026-10-20")
        val dues = listOf(KnownDue(DueSource.RENEWAL, "Netflix", d("2026-10-28"), inr("649")), KnownDue(DueSource.BILL, "Rent", d("2026-11-05"), inr("18000")))
        val f = ready(ForecastInput(today, history("2026-01-01", "2026-10-20"), dues, salaries))
        val outlook = assertIs<SalaryOutlook.Expected>(f.salary)
        val u = outlook.untilSalary
        assertEquals(d("2026-11-01"), u.salary.expectedOn)
        assertEquals(DateRange(d("2026-10-21"), d("2026-10-31")), u.window)
        // Spent 1–20 Oct (incl. rent): 25,800. Expected 21–31 Oct: 649 + 4,200. Rent on 5 Nov is after payday.
        assertEquals(inr("25800.00"), u.spentSince)
        assertEquals(inr("4849.00"), u.expected.setScale(2))
        assertEquals(inr("54351.00"), u.left)
        val (low, high) = assertNotNull(u.leftRange)
        assertTrue(low <= u.left && u.left <= high)
    }

    @Test
    fun `money left has no range until three past months are known`() {
        val f = ready(ForecastInput(d("2026-10-20"), history("2026-07-15", "2026-10-20"), emptyList(), salaries))
        val u = assertIs<SalaryOutlook.Expected>(f.salary).untilSalary
        assertNull(u.range)
        assertNull(u.leftRange)
    }

    @Test
    fun `when payday is tomorrow nothing more is expected before it`() {
        val f = ready(ForecastInput(d("2026-10-31"), history("2026-01-01", "2026-10-31"), emptyList(), salaries))
        val u = assertIs<SalaryOutlook.Expected>(f.salary).untilSalary
        assertNull(u.window)
        assertEquals(0, u.expected.signum())
        assertEquals((inr("85000") - u.spentSince).setScale(2), u.left)
    }

    @Test
    fun `money left can be negative when the forecast runs out first`() {
        val big = salaries.map { it.copy(amount = inr("20000")) }
        val f = ready(ForecastInput(d("2026-10-20"), history("2026-01-01", "2026-10-20"), emptyList(), big))
        assertTrue(assertIs<SalaryOutlook.Expected>(f.salary).untilSalary.left.signum() < 0)
    }

    @Test
    fun `a late salary and no salary are outlooks without a money-left number`() {
        val h = history("2026-01-01", "2026-10-05")
        val late = ready(ForecastInput(d("2026-10-05"), h, emptyList(), salaries.dropLast(1)))
        assertEquals(d("2026-10-01"), assertIs<SalaryOutlook.Late>(late.salary).salary.expectedOn)
        val none = ready(ForecastInput(d("2026-10-05"), h, emptyList(), emptyList()))
        assertEquals(NoSalaryReason.NO_REGULAR_CREDIT, assertIs<SalaryOutlook.Unknown>(none.salary).reason)
    }

    @Test
    fun `a forecaster needs at least the run-rate's minimum history`() {
        assertFailsWith<IllegalArgumentException> { MonthEndForecaster(minHistoryDays = 10) }
    }

    @Test
    fun `range settings are validated and a range runs low to high`() {
        assertFailsWith<IllegalArgumentException> { EmpiricalInterval(monthsBack = 2, minMonths = 3) }
        assertFailsWith<IllegalArgumentException> { EmpiricalInterval(minMonths = 1, monthsBack = 1) }
        assertFailsWith<IllegalArgumentException> { EmpiricalInterval(level = 1.0) }
        assertFailsWith<IllegalArgumentException> { EmpiricalInterval(neighbourDays = 9) }
        assertFailsWith<IllegalArgumentException> { ForecastRange(inr("2"), inr("1"), 80, 3) }
    }

    @Test
    fun `range ends come from past errors and the everyday part never goes below nothing`() {
        val errors = RateErrors(listOf(inr("-500"), inr("-100"), inr("0"), inr("50"), inr("200")), months = 3)
        val interpolated = EmpiricalInterval(quantiles = QuantileMethod.INTERPOLATED).range(inr("10000"), inr("1000"), 10, errors)
        assertNotNull(interpolated)
        // fixed 9,000 + max(0, 1,000 + q × 10)
        assertEquals(inr("9000.00"), interpolated.low)
        assertEquals(inr("11400.00"), interpolated.high)
        val conformal = EmpiricalInterval(quantiles = QuantileMethod.CONFORMAL).range(inr("10000"), inr("1000"), 10, errors)
        assertEquals(inr("12000.00"), conformal?.high)
        assertNull(EmpiricalInterval().range(inr("10000"), inr("1000"), 10, errors.copy(months = 2)))
    }

    @Test
    fun `past errors near the month's end skip days the month doesn't have`() {
        val h = history("2025-09-01", "2026-03-31")
        val errors = EmpiricalInterval().errors(h, d("2026-03-31"))
        // 31 Mar: each earlier month is learnt from on its last days only (no remaining days → skipped), so few errors.
        assertTrue(errors.perDay.size < 12 * 7)
        assertEquals(DateRange(d("2026-02-28"), d("2026-02-28")), EmpiricalInterval.remainingOf(d("2026-02-27")))
        assertNull(EmpiricalInterval.remainingOf(d("2026-02-28")))
        assertNull(EmpiricalInterval.remainingOf(d("2028-02-29")))
    }
}
