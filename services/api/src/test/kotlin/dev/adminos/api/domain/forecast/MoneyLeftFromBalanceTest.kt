package dev.adminos.api.domain.forecast

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** AOS-720 (D-170): money left until salary starts from recent bank balances when known, else from the last salary. */
class MoneyLeftFromBalanceTest {
    private fun d(s: String) = LocalDate.parse(s)
    private val today = d("2026-10-20")

    /** ₹300 a day from 1 Jan, and an ₹85,000 salary on the 1st of each month. */
    private fun input(balances: List<BankBalance>): ForecastInput {
        val days = generateSequence(d("2026-01-01")) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }
            .map { SpendDay(it, BigDecimal("300"), BigDecimal.ZERO) }.toList()
        val salaries = (1..10).map { IncomeCredit(LocalDate.of(2026, it, 1), "ACME", BigDecimal("85000")) }
        return ForecastInput(today, SpendHistory(days, d("2026-01-01")), emptyList(), salaries, balances)
    }

    private fun until(balances: List<BankBalance>): UntilSalary {
        val f = assertIs<Forecast.Ready>(MonthEndForecaster().forecast(input(balances)))
        return assertIs<SalaryOutlook.Expected>(f.salary).untilSalary
    }

    private fun bal(last4: String, amount: String, on: String, bank: String? = "hdfc") = BankBalance(bank, last4, BigDecimal(amount), d(on), "sms")

    @Test
    fun `without a balance money left is the last salary less spending since it (D-76)`() {
        val u = until(emptyList())
        assertIs<MoneyBasis.LastSalary>(u.basis)
        assertEquals(BigDecimal("6000.00"), u.spentSince) // 1-20 Oct
        assertEquals(d("2026-10-01"), u.spentFrom)
        assertEquals(BigDecimal("75700.00"), u.left) // 85,000 − 6,000 − 11 days × 300
    }

    @Test
    fun `recent balances replace the salary, less spending from the oldest of their days`() {
        val u = until(listOf(bal("1234", "40000.00", "2026-10-18"), bal("5678", "10000.00", "2026-10-19", bank = "sbi")))
        val basis = assertIs<MoneyBasis.Balances>(u.basis)
        assertEquals(BigDecimal("50000.00"), basis.start)
        assertEquals(d("2026-10-18"), basis.asOf)
        assertEquals(d("2026-10-18"), u.spentFrom)
        assertEquals(BigDecimal("900.00"), u.spentSince) // 18, 19 and 20 Oct: the reading's own day in full
        assertEquals(BigDecimal("45800.00"), u.left) // 50,000 − 900 − 3,300
        assertEquals(emptyList(), u.notCounted)
    }

    @Test
    fun `a balance from today still subtracts today's spending, so none after the reading is missed`() {
        val u = until(listOf(bal("1234", "20000.00", "2026-10-20")))
        assertEquals(BigDecimal("300.00"), u.spentSince)
        assertEquals(today, u.spentFrom)
        assertEquals(BigDecimal("16400.00"), u.left)
    }

    @Test
    fun `stale balances and balances from before the salary are listed, not counted`() {
        val u = until(listOf(bal("1234", "40000.00", "2026-10-12"), bal("5678", "500.00", "2026-10-19")))
        assertEquals(listOf("1234" to NotCountedReason.STALE), u.notCounted.map { it.balance.last4 to it.reason })
        assertEquals(BigDecimal("500.00"), u.basis.start)
        val onlyStale = until(listOf(bal("1234", "40000.00", "2026-10-12")))
        assertIs<MoneyBasis.LastSalary>(onlyStale.basis)
        assertEquals(1, onlyStale.notCounted.size)
    }

    @Test
    fun `a balance from before the salary came doesn't hold it`() {
        val earlyMonth = input(listOf(bal("1234", "900.00", "2026-09-30"))).copy(today = d("2026-10-03"),
            history = input(emptyList()).history)
        val f = assertIs<Forecast.Ready>(MonthEndForecaster().forecast(earlyMonth.copy(history = SpendHistory(
            generateSequence(d("2026-01-01")) { it.plusDays(1) }.takeWhile { !it.isAfter(d("2026-10-03")) }.map { SpendDay(it, BigDecimal("300"), BigDecimal.ZERO) }.toList(),
            d("2026-01-01")))))
        val u = assertIs<SalaryOutlook.Expected>(f.salary).untilSalary
        assertIs<MoneyBasis.LastSalary>(u.basis)
        assertEquals(listOf(NotCountedReason.BEFORE_SALARY), u.notCounted.map { it.reason })
    }

    @Test
    fun `an overdrawn account lowers money left, and the range moves with the start`() {
        val u = until(listOf(bal("1234", "-2000.00", "2026-10-20")))
        assertEquals(BigDecimal("-5600.00"), u.left)
        u.leftRange?.let { (low, high) -> assertEquals(true, low <= u.left && u.left <= high) }
    }

    @Test
    fun `a balance basis needs an account`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { MoneyBasis.Balances(emptyList(), today) }
        assertEquals("BankBalance(?, ••1234, 2026-10-20)", bal("1234", "4321.00", "2026-10-20", bank = null).toString())
    }
}
