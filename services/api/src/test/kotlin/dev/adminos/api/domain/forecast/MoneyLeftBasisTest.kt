package dev.adminos.api.domain.forecast

import dev.adminos.api.domain.balances.BalanceRules
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** AOS-720 (D-170): which balances start "money left", picked apart from the forecast. Synthetic data only. */
class MoneyLeftBasisTest {
    private fun d(s: String) = LocalDate.parse(s)
    private val today = d("2026-10-20")
    private val salary = DetectedSalary("ACME", BigDecimal("85000.00"), d("2026-10-01"), BigDecimal("85000.00"), d("2026-11-01"), 3)
    private val picker = MoneyLeftBasisPicker(maxBalanceAgeDays = 7)

    private fun bal(last4: String, amount: String, on: String) = BankBalance("hdfc", last4, BigDecimal(amount), d(on), "sms")

    @Test
    fun `with no balances the last salary is the start`() {
        val picked = picker.pick(today, salary, emptyList())
        assertEquals(MoneyBasis.LastSalary(BigDecimal("85000.00"), d("2026-10-01")), picked.basis)
        assertEquals(emptyList(), picked.notCounted)
    }

    @Test
    fun `a balance exactly at the age limit counts and one a day older is stale`() {
        val picked = picker.pick(today, salary, listOf(bal("1111", "100.00", "2026-10-13"), bal("2222", "200.00", "2026-10-12")))
        val basis = assertIs<MoneyBasis.Balances>(picked.basis)
        assertEquals(listOf("1111"), basis.accounts.map { it.last4 })
        assertEquals(listOf("2222" to NotCountedReason.STALE), picked.notCounted.map { it.balance.last4 to it.reason })
    }

    @Test
    fun `a recent balance from before the salary came is not counted`() {
        val early = d("2026-10-03")
        val picked = picker.pick(early, salary, listOf(bal("1111", "900.00", "2026-09-30"), bal("2222", "50000.00", "2026-10-01")))
        val basis = assertIs<MoneyBasis.Balances>(picked.basis)
        assertEquals(listOf("2222"), basis.accounts.map { it.last4 })
        assertEquals(listOf("1111" to NotCountedReason.BEFORE_SALARY), picked.notCounted.map { it.balance.last4 to it.reason })
        val onlyBefore = picker.pick(early, salary, listOf(bal("1111", "900.00", "2026-09-30")))
        assertIs<MoneyBasis.LastSalary>(onlyBefore.basis)
    }

    @Test
    fun `stale wins over before salary when both apply`() {
        val picked = picker.pick(today, salary, listOf(bal("1111", "900.00", "2026-09-01")))
        assertEquals(listOf(NotCountedReason.STALE), picked.notCounted.map { it.reason })
    }

    @Test
    fun `a balance dated after today counts as today's`() {
        val picked = picker.pick(today, salary, listOf(bal("1111", "300.00", "2026-10-22")))
        val basis = assertIs<MoneyBasis.Balances>(picked.basis)
        assertEquals(today, basis.asOf)
        assertEquals(today, basis.accounts.single().observedOn)
        assertEquals(today, basis.spentSince(today)?.from, "today's spending is still subtracted")
        assertEquals(emptyList(), picked.notCounted)
    }

    @Test
    fun `mixed balances sum the counted ones from the oldest counted day`() {
        val picked = picker.pick(today, salary, listOf(
            bal("1111", "40000.00", "2026-10-18"),
            bal("2222", "-2500.50", "2026-10-20"),
            bal("3333", "99999.00", "2026-10-01"),
            bal("4444", "10.00", "2026-10-25")
        ))
        val basis = assertIs<MoneyBasis.Balances>(picked.basis)
        assertEquals(listOf("1111", "2222", "4444"), basis.accounts.map { it.last4 })
        assertEquals(BigDecimal("37509.50"), basis.start)
        assertEquals(d("2026-10-18"), basis.asOf)
        assertEquals(d("2026-10-18"), basis.spentSince(today)?.from, "the oldest day counts in full")
        assertEquals(listOf("3333" to NotCountedReason.STALE), picked.notCounted.map { it.balance.last4 to it.reason })
    }

    @Test
    fun `the default limit is the balances' stale limit and a negative one is refused`() {
        assertEquals(BalanceRules.MAX_AGE_DAYS, MoneyLeftBasisPicker().maxBalanceAgeDays)
        assertEquals(MoneyLeftBasisPicker.MAX_BALANCE_AGE_DAYS, MonthEndForecaster.MAX_BALANCE_AGE_DAYS)
        assertFailsWith<IllegalArgumentException> { MoneyLeftBasisPicker(maxBalanceAgeDays = -1) }
    }

    @Test
    fun `several balances start from the oldest of their days, and a basis from tomorrow subtracts nothing`() {
        val picked = picker.pick(today, salary, listOf(bal("1111", "100.00", "2026-10-19"), bal("2222", "200.00", "2026-10-15")))
        val basis = assertIs<MoneyBasis.Balances>(picked.basis)
        assertEquals(d("2026-10-15"), basis.asOf)
        assertEquals(BigDecimal("300.00"), basis.start)
        assertEquals(dev.adminos.api.domain.analytics.DateRange(d("2026-10-15"), today), basis.spentSince(today))
        assertEquals(null, MoneyBasis.Balances(basis.accounts, d("2026-10-21")).spentSince(today))
        assertFailsWith<IllegalArgumentException> { MoneyBasis.Balances(emptyList(), today) }
        assertFailsWith<IllegalArgumentException> { MoneyLeftBasisPicker(maxBalanceAgeDays = -1) }
    }

    @Test
    fun `a balance never prints its amount, with or without a bank`() {
        val named = bal("1111", "98765.43", "2026-10-19")
        for (b in listOf(named, named.copy(bankCode = null))) {
            val s = b.toString()
            assertEquals(false, "98765" in s, s)
            assertEquals(true, "1111" in s, s)
        }
        assertEquals(true, "?" in named.copy(bankCode = null).toString())
    }
}
