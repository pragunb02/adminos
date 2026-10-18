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
    // ── Projected monthly bills (AOS-721) ──

    private fun projected(on: String, amount: String = "1200", name: String = "Sample Power") =
        ProjectedBill(dev.adminos.api.domain.financial.BillType.ELECTRICITY, name, java.time.YearMonth.from(d(on)), d(on), inr(amount),
            monthsSeen = 3, seenFrom = java.time.YearMonth.of(2026, 7), seenTo = java.time.YearMonth.of(2026, 9))

    private fun projectedPart(parts: List<ComponentEstimate>) = parts.filterIsInstance<ProjectedBillsEstimate>().single()

    @Test
    fun `a projected bill later this month adds to the month-end as its own part`() {
        val bill = projected("2026-10-26")
        val f = ready(ForecastInput(d("2026-10-20"), history("2026-05-01", "2026-10-20"), emptyList(), emptyList(), projectedBills = listOf(bill))).monthEnd
        assertEquals(listOf(bill), projectedPart(f.parts).items)
        // 25,800 spent + 1,200 projected + 4,200 everyday.
        assertEquals(inr("31200.00"), f.total)
    }

    @Test
    fun `projected bills come after the due sources and before everyday spending`() {
        val f = ready(ForecastInput(d("2026-10-20"), history("2026-05-01", "2026-10-20"), emptyList(), emptyList(), projectedBills = listOf(projected("2026-10-26")))).monthEnd
        assertTrue(f.parts.dropLast(2).all { it is ComponentEstimate.Dues })
        assertIs<ProjectedBillsEstimate>(f.parts[f.parts.size - 2])
        assertIs<ComponentEstimate.RunRate>(f.parts.last())
    }

    @Test
    fun `a projected bill due today or earlier is not counted`() {
        val f = ready(ForecastInput(d("2026-10-20"), history("2026-05-01", "2026-10-20"), emptyList(), emptyList(),
            projectedBills = listOf(projected("2026-10-20"), projected("2026-10-12")))).monthEnd
        assertTrue(projectedPart(f.parts).items.isEmpty())
        assertEquals(inr("30000.00"), f.total)
    }

    @Test
    fun `a projected bill next month is outside the month-end`() {
        val f = ready(ForecastInput(d("2026-10-20"), history("2026-05-01", "2026-10-20"), emptyList(), emptyList(), projectedBills = listOf(projected("2026-11-03")))).monthEnd
        assertEquals(0, projectedPart(f.parts).amount.signum())
    }

    @Test
    fun `a projected bill before payday is taken from the money left`() {
        val withBill = ready(ForecastInput(d("2026-10-20"), history("2026-01-01", "2026-10-20"), emptyList(), salaries, projectedBills = listOf(projected("2026-10-26"))))
        val without = ready(ForecastInput(d("2026-10-20"), history("2026-01-01", "2026-10-20"), emptyList(), salaries))
        val u = assertIs<SalaryOutlook.Expected>(withBill.salary).untilSalary
        assertEquals(listOf("Sample Power"), projectedPart(u.parts).items.map { it.name })
        assertEquals(assertIs<SalaryOutlook.Expected>(without.salary).untilSalary.left - inr("1200"), u.left)
    }

    @Test
    fun `a projected bill after the month's end but before payday counts only until salary`() {
        val onFifth = listOf("2026-06-05", "2026-07-05", "2026-08-05", "2026-09-05", "2026-10-05").map { IncomeCredit(d(it), "ACME", inr("85000")) }
        val f = ready(ForecastInput(d("2026-10-20"), history("2026-01-01", "2026-10-20"), emptyList(), onFifth, projectedBills = listOf(projected("2026-11-03"))))
        val u = assertIs<SalaryOutlook.Expected>(f.salary).untilSalary
        assertEquals(DateRange(d("2026-10-21"), d("2026-11-04")), u.window)
        assertEquals(inr("1200"), projectedPart(u.parts).amount)
        assertEquals(0, projectedPart(f.monthEnd.parts).amount.signum())
    }

    @Test
    fun `a projected bill after payday is not taken from the money left`() {
        val f = ready(ForecastInput(d("2026-10-20"), history("2026-01-01", "2026-10-20"), emptyList(), salaries, projectedBills = listOf(projected("2026-11-03"))))
        assertEquals(0, projectedPart(assertIs<SalaryOutlook.Expected>(f.salary).untilSalary.parts).amount.signum())
    }

    @Test
    fun `a projected bill moves the range like a known due without widening it`() {
        val base = ForecastInput(d("2026-10-20"), history("2026-01-01", "2026-10-20"), emptyList(), emptyList())
        val without = assertNotNull(ready(base).monthEnd.range)
        val with = assertNotNull(ready(base.copy(projectedBills = listOf(projected("2026-10-26")))).monthEnd.range)
        assertEquals(without.low + inr("1200"), with.low)
        assertEquals(without.high + inr("1200"), with.high)
    }
    // ── The owner's salary choice (AOS-722) ──

    private fun choice(payer: String?, day: Int, amount: String = "85000") =
        SalaryChoice(payer, day, inr(amount), SalaryChoiceKind.CORRECTED, java.time.Instant.parse("2026-10-18T05:00:00Z"))

    private fun outlook(credits: List<IncomeCredit>, choice: SalaryChoice?) =
        ready(ForecastInput(d("2026-10-20"), history("2026-01-01", "2026-10-20"), emptyList(), credits, salaryChoice = choice)).salary

    @Test
    fun `a corrected salary day moves the next payday and the until-salary window`() {
        val u = assertIs<SalaryOutlook.Expected>(outlook(salaries, choice("ACME", 5))).untilSalary
        assertEquals(d("2026-11-05"), u.salary.expectedOn)
        assertEquals(5, u.salary.usualDay)
        assertEquals(SalarySource.CONFIRMED, u.salary.source)
        assertEquals(DateRange(d("2026-10-21"), d("2026-11-04")), u.window)
    }

    @Test
    fun `a corrected payer picks that payer's credits over a larger salary from someone else`() {
        val side = listOf("2026-06-10", "2026-07-10", "2026-08-10", "2026-09-10", "2026-10-09").map { IncomeCredit(d(it), "Side Studio", inr("30000")) }
        assertEquals("ACME", assertIs<SalaryOutlook.Expected>(outlook(salaries + side, null)).untilSalary.salary.payer)
        val s = assertIs<SalaryOutlook.Expected>(outlook(salaries + side, choice("Side Studio", 10, "30000"))).untilSalary.salary
        assertEquals("Side Studio", s.payer)
        assertEquals(d("2026-10-09"), s.lastReceivedOn)
        assertEquals(d("2026-11-10"), s.expectedOn)
        assertEquals(inr("30000.00"), s.lastAmount.setScale(2))
    }

    @Test
    fun `a confirmed payer not seen in the credits is unknown and says it came from the choice`() {
        val u = assertIs<SalaryOutlook.Unknown>(outlook(salaries, choice("Nobody Ltd", 1)))
        assertEquals(NoSalaryReason.NOT_SEEN, u.reason)
        assertTrue(u.chosen)
    }

    @Test
    fun `without a choice the salary is detected as before`() {
        val s = assertIs<SalaryOutlook.Expected>(outlook(salaries, null)).untilSalary.salary
        assertEquals(SalarySource.DETECTED, s.source)
        assertEquals(d("2026-11-01"), s.expectedOn)
        assertEquals(false, assertIs<SalaryOutlook.Unknown>(outlook(emptyList(), null)).chosen)
    }
}
