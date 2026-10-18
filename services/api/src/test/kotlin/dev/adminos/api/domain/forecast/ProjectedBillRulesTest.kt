package dev.adminos.api.domain.forecast

import dev.adminos.api.domain.financial.BillType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-721 (D-171): a projected bill holds its own rules and never prints its amount or biller. Synthetic data only. */
class ProjectedBillRulesTest {
    private val month = YearMonth.of(2026, 10)
    private fun bill(name: String = "Example Power", amount: String = "2350.50", seen: Int = 3) =
        ProjectedBill(BillType.ELECTRICITY, name, month, LocalDate.of(2026, 10, 25), BigDecimal(amount), seen, YearMonth.of(2026, 7), YearMonth.of(2026, 9))

    @Test
    fun `a projected bill needs a name, a positive amount and at least one month seen`() {
        bill()
        assertFailsWith<IllegalArgumentException> { bill(name = " ") }
        assertFailsWith<IllegalArgumentException> { bill(amount = "0") }
        assertFailsWith<IllegalArgumentException> { bill(amount = "-1") }
        assertFailsWith<IllegalArgumentException> { bill(seen = 0) }
    }

    @Test
    fun `it prints its type and dates, never the amount or the biller`() {
        val s = bill().toString()
        assertTrue("ELECTRICITY" in s && "2026-10-25" in s, s)
        assertFalse("2350" in s || "Example Power" in s, s)
    }
}
