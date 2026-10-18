package dev.adminos.api.domain.wealth.returns

import dev.adminos.api.domain.wealth.InvestmentTransaction
import dev.adminos.api.domain.wealth.InvestmentTxnType
import dev.adminos.api.domain.wealth.Quantity
import dev.adminos.api.domain.wealth.WealthFixtures
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AOS-812: XIRR against known values (Excel's documented example; the research's worked example; values computed
 * independently at 60 digits), its edge cases, and an NPV ≈ 0 property. Returns under a year are absolute.
 */
class XirrTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun f(date: String, amount: String) = CashFlow(d(date), BigDecimal(amount))
    private fun rate(flows: List<CashFlow>) = (Xirr.solve(flows) as? XirrResult.Rate)?.rate ?: error("no rate: ${Xirr.solve(flows)}")
    private fun close(expected: String, actual: BigDecimal, places: Int = 9) =
        assertTrue((BigDecimal(expected) - actual).abs() < BigDecimal.ONE.movePointLeft(places), "expected $expected, was $actual")

    @Test
    fun `ln and exp are exact to far more places than a rate needs`() {
        close("0.693147180559945309417232121458176568", BigMath.ln(BigDecimal(2)), 30)
        close("2.302585092994045684017991454684364208", BigMath.ln(BigDecimal(10)), 30)
        close("-9.210340371976182736", BigMath.ln(BigDecimal("0.0001")), 18)
        close("2.718281828459045235360287471352662498", BigMath.exp(BigDecimal.ONE), 30)
        close("0.000045399929762484851535591515560550", BigMath.exp(BigDecimal(-10)), 30)
        close("1.1", BigMath.pow(BigDecimal("1.21"), BigDecimal("0.5")), 30)
        assertFailsWith<IllegalArgumentException> { BigMath.ln(BigDecimal.ZERO) }
    }

    @Test
    fun `Excel's documented XIRR example gives 37 point 34 percent`() {
        val flows = listOf(f("2008-01-01", "-10000"), f("2008-03-01", "2750"), f("2008-10-30", "4250"), f("2009-02-15", "3250"), f("2009-04-01", "2750"))
        close("0.3733625335", rate(flows))
    }

    @Test
    fun `the research's worked example is about 20 point 3 percent`() {
        close("0.2025568892", rate(listOf(f("2025-01-01", "-100000"), f("2025-07-01", "-100000"), f("2026-01-01", "230000"))))
    }

    @Test
    fun `one year plus or minus ten percent is exactly ten percent`() {
        close("0.1", rate(listOf(f("2025-01-01", "-1000"), f("2026-01-01", "1100"))), 10)
        close("-0.1", rate(listOf(f("2025-01-01", "-1000"), f("2026-01-01", "900"))), 10)
        close("0", rate(listOf(f("2025-01-01", "-1000"), f("2026-01-01", "1000"))), 10)
    }

    @Test
    fun `no rate is shown when none or several fit`() {
        assertEquals(XirrResult.NotDefined(NotDefinedReason.NO_SIGN_CHANGE), Xirr.solve(listOf(f("2025-01-01", "-1000"), f("2025-06-01", "-1000"))))
        assertEquals(XirrResult.NotDefined(NotDefinedReason.SAME_DAY), Xirr.solve(listOf(f("2025-01-01", "-1000"), f("2025-01-01", "1100"))))
        assertEquals(XirrResult.NotDefined(NotDefinedReason.TOO_FEW_FLOWS), Xirr.solve(listOf(f("2025-01-01", "-1000"))))
        assertEquals(XirrResult.NotDefined(NotDefinedReason.TOO_FEW_FLOWS), Xirr.solve(listOf(f("2025-01-01", "-1000"), f("2026-01-01", "0"))))
        // −100, +230, −132: both 10% and 20% fit (the classic two-root case).
        assertEquals(XirrResult.NotDefined(NotDefinedReason.MULTIPLE_RATES), Xirr.solve(listOf(f("2023-01-01", "-100"), f("2024-01-01", "230"), f("2025-01-01", "-132"))))
        // A 100× gain in a month is beyond +1,000% a year.
        assertEquals(XirrResult.NotDefined(NotDefinedReason.OUT_OF_RANGE), Xirr.solve(listOf(f("2025-01-01", "-1"), f("2025-02-01", "100"))))
    }

    @Test
    fun `a total loss is close to minus 100 percent and still converges`() {
        val r = rate(listOf(f("2020-01-01", "-1000"), f("2025-01-01", "0.01")))
        assertTrue(r < BigDecimal("-0.89") && r > BigDecimal("-0.9999"))
        assertTrue(Xirr.npv(listOf(f("2020-01-01", "-1000"), f("2025-01-01", "0.01")), r).abs() < BigDecimal("0.01"))
    }

    @Test
    fun `the rate found always makes NPV zero to the paisa`() {
        val rnd = Random(812)
        repeat(60) { trial ->
            val start = LocalDate.of(2015, 1, 1)
            val flows = (0 until rnd.nextInt(2, 60)).map { i -> CashFlow(start.plusDays(i * 30L + rnd.nextLong(0, 10)), BigDecimal(-rnd.nextInt(500, 50_000))) }
            val total = flows.fold(BigDecimal.ZERO) { a, c -> a - c.amount }
            val value = total.multiply(BigDecimal(rnd.nextDouble(0.5, 2.5).toString()), MathContext.DECIMAL64).setScale(2, java.math.RoundingMode.HALF_EVEN)
            val all = flows + CashFlow(start.plusYears(6), value)
            when (val r = Xirr.solve(all)) {
                is XirrResult.Rate -> assertTrue(Xirr.npv(all, r.rate).abs() < BigDecimal("0.01"), "trial $trial: NPV ${Xirr.npv(all, r.rate)}")
                is XirrResult.NotDefined -> error("trial $trial: one sign change must give a rate, got ${r.reason}")
            }
        }
    }

    @Test
    fun `twenty years of monthly SIPs solve quickly`() {
        val flows = (0 until 240).map { CashFlow(LocalDate.of(2006, 1, 5).plusMonths(it.toLong()), BigDecimal("-10000")) } + f("2026-01-05", "9900000")
        val started = System.nanoTime()
        val r = rate(flows)
        assertTrue(System.nanoTime() - started < 5_000_000_000, "took ${(System.nanoTime() - started) / 1_000_000} ms")
        assertTrue(Xirr.npv(flows, r).abs() < BigDecimal("0.01"))
    }

    @Test
    fun `under a year the absolute return is shown, from a year the annual rate`() {
        // +3% in 20 days would annualise to about 71%: shown as 3% absolute.
        val short = Returns.of(listOf(f("2026-01-01", "-1000")), BigDecimal("1030"), d("2026-01-21"))
        assertEquals(ReturnBasis.ABSOLUTE, short.basis); close("0.03", short.shown ?: error("none"), 10)
        assertTrue((short.xirr as XirrResult.Rate).rate > BigDecimal("0.7"))
        val long = Returns.of(listOf(f("2025-01-01", "-1000")), BigDecimal("1100"), d("2026-01-01"))
        assertEquals(ReturnBasis.ANNUALISED, long.basis); close("0.1", long.shown ?: error("none"), 10)
        val none = Returns.of(listOf(f("2020-01-01", "-1"), f("2020-01-02", "1000")), BigDecimal.ZERO, d("2021-06-01"))
        assertEquals(ReturnBasis.NONE, none.basis); assertNull(none.shown); assertEquals(NotDefinedReason.OUT_OF_RANGE.text, none.reason)
        val nothingIn = Returns.of(emptyList(), BigDecimal.ZERO, d("2026-01-01"))
        assertNull(nothingIn.absolute); assertEquals(ReturnBasis.NONE, nothingIn.basis)
    }

    @Test
    fun `ledger rows become investor cash flows by type`() {
        val acct = UUID.randomUUID(); val fund = UUID.randomUUID()
        var n = 0
        fun r(t: InvestmentTxnType, units: String?, amount: String?) = InvestmentTransaction(userId = UUID.randomUUID(), accountId = acct, instrumentId = fund,
            batchId = null, type = t, tradeDate = d("2025-01-01").plusDays((n++).toLong()), units = units?.let(Quantity::parse), unitPrice = null,
            amount = amount?.let(::BigDecimal), sourceRef = WealthFixtures.ref(n))
        val flows = Returns.cashFlows(listOf(
            r(InvestmentTxnType.SIP_BUY, "10", "1000.00"), r(InvestmentTxnType.STAMP_DUTY, null, "0.05"), r(InvestmentTxnType.SELL, "-5", "600.00"),
            r(InvestmentTxnType.DIVIDEND_PAYOUT, null, "50.00"), r(InvestmentTxnType.TDS, null, "5.00"), r(InvestmentTxnType.DIVIDEND_REINVEST, "1", "20.00"),
            r(InvestmentTxnType.BONUS, "5", null), r(InvestmentTxnType.REVERSAL, "-10", "1000.00"), r(InvestmentTxnType.REVERSAL, "2", "200.00"),
            r(InvestmentTxnType.MERGER_IN, "3", "300.00"), r(InvestmentTxnType.BUY, "1", "0.00"),
        ))
        assertEquals(listOf("-1000.00", "-0.05", "600.00", "50.00", "-5.00", "1000.00", "-200.00"), flows.map { it.amount.toPlainString() })
    }

    @Test
    fun `Modified Dietz weights contributions by the time they were invested`() {
        // 1,00,000 at the start, 10,000 added half-way, 1,21,000 at the end: (121000 − 100000 − 10000) ÷ (100000 + 5000).
        val r = ModifiedDietz.of(d("2025-01-01"), d("2025-12-31"), BigDecimal("100000"), BigDecimal("121000"), listOf(f("2025-07-02", "10000")))
        close("0.1047619048", r ?: error("none"), 9)
        assertNull(ModifiedDietz.of(d("2025-01-01"), d("2025-12-31"), BigDecimal.ZERO, BigDecimal.ZERO, emptyList()))
        assertFailsWith<IllegalArgumentException> { ModifiedDietz.of(d("2025-01-01"), d("2025-01-01"), BigDecimal.ONE, BigDecimal.ONE, emptyList()) }
        assertFailsWith<IllegalArgumentException> { ModifiedDietz.of(d("2025-01-01"), d("2025-02-01"), BigDecimal.ONE, BigDecimal.ONE, listOf(f("2025-03-01", "1"))) }
        // The research's TWR: 1.10 × (230000 ÷ 210000) − 1 ≈ 20.5%.
        close("0.2047619048", ModifiedDietz.chain(listOf(BigDecimal("0.10"), BigDecimal("230000").divide(BigDecimal("210000"), MathContext.DECIMAL128) - BigDecimal.ONE)), 9)
    }
}
