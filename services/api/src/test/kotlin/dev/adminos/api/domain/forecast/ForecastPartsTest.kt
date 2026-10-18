package dev.adminos.api.domain.forecast

import dev.adminos.api.domain.analytics.DateRange
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-714: the forecast's building blocks — history, statistics, run-rate and components. */
class ForecastPartsTest {

    private fun d(s: String) = LocalDate.parse(s)
    private fun inr(s: String) = BigDecimal(s)

    /** Every day from [from] to [to] spends [amount] (weekends [weekend]). */
    private fun daily(from: String, to: String, amount: String, weekend: String = amount): SpendHistory {
        val days = generateSequence(d(from)) { it.plusDays(1) }.takeWhile { !it.isAfter(d(to)) }
            .map { SpendDay(it, inr(if (DayType.of(it) == DayType.WEEKEND) weekend else amount), BigDecimal.ZERO) }.toList()
        return SpendHistory(days, d(from))
    }

    // ── SpendHistory ──

    @Test
    fun `two rows for one day add up and totals span inclusive ranges`() {
        val h = SpendHistory(listOf(SpendDay(d("2026-10-01"), inr("100"), inr("50")), SpendDay(d("2026-10-01"), inr("20"), inr("0")),
            SpendDay(d("2026-10-03"), inr("-30"), inr("0"))), d("2026-09-15"))
        assertEquals(inr("170"), h.totalOn(d("2026-10-01")))
        assertEquals(inr("120"), h.discretionaryOn(d("2026-10-01")))
        assertEquals(BigDecimal.ZERO, h.totalOn(d("2026-10-02")))
        assertEquals(inr("140"), h.total(DateRange(d("2026-10-01"), d("2026-10-03"))))
        assertEquals(inr("90"), h.discretionary(DateRange(d("2026-10-01"), d("2026-10-03"))))
        assertEquals(inr("50"), SpendDay(d("2026-10-01"), inr("20"), inr("30")).total)
    }

    @Test
    fun `history length counts from the first activity and is zero before it`() {
        val h = SpendHistory(emptyList(), d("2026-10-01"))
        assertEquals(0, h.knownDaysUpTo(d("2026-09-30")))
        assertEquals(1, h.knownDaysUpTo(d("2026-10-01")))
        assertEquals(31, h.knownDaysUpTo(d("2026-10-31")))
        assertTrue(h.known(d("2026-10-01")))
        assertFalse(h.known(d("2026-09-30")))
    }

    @Test
    fun `spending before the first activity is refused`() {
        assertFailsWith<IllegalArgumentException> { SpendHistory(listOf(SpendDay(d("2026-09-01"), inr("1"), inr("0"))), d("2026-10-01")) }
    }

    @Test
    fun `an income credit is positive`() {
        assertFailsWith<IllegalArgumentException> { IncomeCredit(d("2026-10-01"), "ACME", BigDecimal.ZERO) }
        assertFailsWith<IllegalArgumentException> { IncomeCredit(d("2026-10-01"), "ACME", inr("-5")) }
    }

    // ── Stats ──

    @Test
    fun `median of odd, even and no values`() {
        assertEquals(inr("2"), Stats.median(listOf(inr("3"), inr("1"), inr("2"))))
        assertEquals(0, inr("2.5").compareTo(Stats.median(listOf(inr("3"), inr("1"), inr("2"), inr("4")))))
        assertEquals(BigDecimal.ZERO, Stats.median(emptyList()))
    }

    @Test
    fun `trimmed mean drops each end and a plain mean keeps all`() {
        val v = (1..10).map { BigDecimal(it) } + BigDecimal(1000)
        assertEquals(0, inr("6").compareTo(Stats.trimmedMean(v, 0.1)))
        assertEquals(0, inr("95.909091").compareTo(Stats.trimmedMean(v, 0.0)))
        assertEquals(BigDecimal.ZERO, Stats.trimmedMean(emptyList(), 0.1))
    }

    @Test
    fun `quantiles interpolate between order statistics and need values`() {
        val v = listOf(inr("10"), inr("20"), inr("30"), inr("40"), inr("50"))
        assertEquals(0, inr("10").compareTo(Stats.quantile(v, 0.0)))
        assertEquals(0, inr("50").compareTo(Stats.quantile(v, 1.0)))
        assertEquals(0, inr("14").compareTo(Stats.quantile(v, 0.1)))
        assertFailsWith<IllegalArgumentException> { Stats.quantile(emptyList(), 0.5) }
        assertFailsWith<IllegalArgumentException> { Stats.quantile(v, 1.5) }
    }

    // ── RunRateModel ──

    @Test
    fun `too little known history fits no rate`() {
        val h = daily("2026-10-01", "2026-10-20", "100")
        assertNull(RunRateModel().fit(h, d("2026-10-20")))
        assertNull(RunRateModel().fit(h, d("2026-09-30")))
    }

    @Test
    fun `weekdays and weekends get their own rate over the last 56 days`() {
        val h = daily("2026-07-01", "2026-10-20", "200", weekend = "900")
        val fit = RunRateModel().fit(h, d("2026-10-20")) ?: error("expected a fit")
        assertTrue(fit.split)
        assertEquals(inr("200.00"), fit.rateOf(DayType.WEEKDAY))
        assertEquals(inr("900.00"), fit.rateOn(d("2026-10-18")))
        assertEquals(DateRange(d("2026-08-26"), d("2026-10-20")), fit.lookback)
        assertEquals(RateEstimator.WEEKLY_MEDIAN, fit.estimator)
    }

    @Test
    fun `the lookback starts no earlier than the history`() {
        val h = daily("2026-09-01", "2026-10-20", "100")
        assertEquals(d("2026-09-01"), RunRateModel().fit(h, d("2026-10-20"))?.lookback?.from)
    }

    @Test
    fun `an unsplit model or too few days of a kind gives one pooled rate`() {
        val h = daily("2026-07-01", "2026-10-20", "200", weekend = "900")
        val pooled = RunRateModel(splitWeekend = false, estimator = RateEstimator.MEDIAN).fit(h, d("2026-10-20")) ?: error("fit")
        assertFalse(pooled.split)
        assertEquals(pooled.rateOf(DayType.WEEKDAY), pooled.rateOf(DayType.WEEKEND))
        assertEquals(inr("200.00"), pooled.rateOf(DayType.WEEKDAY))
        val fewWeekends = RunRateModel(minDaysPerType = 20).fit(h, d("2026-10-20")) ?: error("fit")
        assertFalse(fewWeekends.split)
    }

    @Test
    fun `the weekly median ignores one big purchase and still sees sparse spending`() {
        // 1,400 every Sunday only, plus one 50,000 purchase: a daily median would say 0.
        val days = generateSequence(d("2026-08-01")) { it.plusDays(1) }.takeWhile { !it.isAfter(d("2026-10-20")) }
            .mapNotNull { day ->
                when {
                    day == d("2026-10-07") -> SpendDay(day, inr("50000"), BigDecimal.ZERO)
                    day.dayOfWeek == java.time.DayOfWeek.SUNDAY -> SpendDay(day, inr("1400"), BigDecimal.ZERO)
                    else -> null
                }
            }.toList()
        val h = SpendHistory(days, d("2026-08-01"))
        val weekly = RunRateModel(splitWeekend = false).fit(h, d("2026-10-20")) ?: error("fit")
        assertEquals(inr("200.00"), weekly.rateOf(DayType.WEEKDAY))
        val median = RunRateModel(splitWeekend = false, estimator = RateEstimator.MEDIAN).fit(h, d("2026-10-20")) ?: error("fit")
        assertEquals(inr("0.00"), median.rateOf(DayType.WEEKDAY))
        val trimmed = RunRateModel(splitWeekend = false, estimator = RateEstimator.TRIMMED_MEAN).fit(h, d("2026-10-20")) ?: error("fit")
        assertTrue(trimmed.rateOf(DayType.WEEKDAY) < inr("200"))
    }

    @Test
    fun `run-rate settings and fits are validated`() {
        assertFailsWith<IllegalArgumentException> { RunRateModel(minLookbackDays = 3) }
        assertFailsWith<IllegalArgumentException> { RunRateModel(lookbackDays = 20, minLookbackDays = 28) }
        assertFailsWith<IllegalArgumentException> { RunRateModel(trim = 0.5) }
        assertFailsWith<IllegalArgumentException> { RunRateModel(minDaysPerType = 0) }
        val r = DateRange(d("2026-10-01"), d("2026-10-28"))
        assertFailsWith<IllegalArgumentException> { RunRateFit(mapOf(DayType.WEEKDAY to inr("1")), r, true, RateEstimator.MEDIAN) }
        assertFailsWith<IllegalArgumentException> { RunRateFit(mapOf(DayType.WEEKDAY to inr("1"), DayType.WEEKEND to inr("2")), r, false, RateEstimator.MEDIAN) }
    }

    // ── Components ──

    private val fit = RunRateFit(mapOf(DayType.WEEKDAY to inr("100.50"), DayType.WEEKEND to inr("400.00")), DateRange(d("2026-08-26"), d("2026-10-20")), true, RateEstimator.WEEKLY_MEDIAN)

    @Test
    fun `everyday spending is each kind of day's rate times its days`() {
        // 21–31 Oct 2026: 8 weekdays and 3 weekend days (24, 25, 31).
        val e = RunRateComponent(fit).estimate(DateRange(d("2026-10-21"), d("2026-10-31")))
        assertEquals(mapOf(DayType.WEEKDAY to 8, DayType.WEEKEND to 3), e.days)
        assertEquals(inr("2004.00"), e.amount)
    }

    @Test
    fun `no window expects nothing`() {
        val e = RunRateComponent(fit).estimate(null)
        assertEquals(inr("0.00"), e.amount)
        assertEquals(BigDecimal.ZERO, KnownDuesComponent(DueSource.BILL, listOf(KnownDue(DueSource.BILL, "Rent", d("2026-10-25"), inr("1")))).estimate(null).amount)
    }

    @Test
    fun `a dues component counts only its own source inside the window, by date`() {
        val dues = listOf(
            KnownDue(DueSource.BILL, "Tata Power", d("2026-10-28"), inr("2350.50")),
            KnownDue(DueSource.BILL, "Airtel", d("2026-10-22"), inr("999")),
            KnownDue(DueSource.BILL, "Rent", d("2026-11-05"), inr("18000")),
            KnownDue(DueSource.RENEWAL, "Netflix", d("2026-10-25"), inr("649"))
        )
        val bills = KnownDuesComponent(DueSource.BILL, dues).estimate(DateRange(d("2026-10-21"), d("2026-10-31")))
        assertEquals(listOf("Airtel", "Tata Power"), bills.items.map { it.name })
        assertEquals(inr("3349.50"), bills.amount)
        val all = componentsFor(dues, fit).map { it.estimate(DateRange(d("2026-10-21"), d("2026-10-31"))) }
        assertEquals(listOf(DueSource.BILL, DueSource.RENEWAL, DueSource.MANDATE), all.filterIsInstance<ComponentEstimate.Dues>().map { it.source })
        assertTrue(all.last() is ComponentEstimate.RunRate)
    }

    @Test
    fun `a known due has a name and a positive amount`() {
        assertFailsWith<IllegalArgumentException> { KnownDue(DueSource.BILL, " ", d("2026-10-01"), inr("1")) }
        assertFailsWith<IllegalArgumentException> { KnownDue(DueSource.BILL, "Rent", d("2026-10-01"), BigDecimal.ZERO) }
    }

    @Test
    fun `weekends are Saturday and Sunday`() {
        assertEquals(DayType.WEEKEND, DayType.of(d("2026-10-17")))
        assertEquals(DayType.WEEKEND, DayType.of(d("2026-10-18")))
        assertEquals(DayType.WEEKDAY, DayType.of(d("2026-10-19")))
    }
}
