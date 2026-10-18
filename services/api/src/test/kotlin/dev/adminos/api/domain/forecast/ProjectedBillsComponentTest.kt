package dev.adminos.api.domain.forecast

import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.financial.BillType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AOS-721: projected bills count on their expected days, inside the window only. */
class ProjectedBillsComponentTest {

    private fun d(s: String) = LocalDate.parse(s)
    private fun projected(on: String, amount: String, name: String = "Sample Power Co") =
        ProjectedBill(BillType.ELECTRICITY, name, YearMonth.from(d(on)), d(on), BigDecimal(amount), 3, YearMonth.from(d(on)).minusMonths(3), YearMonth.from(d(on)).minusMonths(1))

    private val bills = listOf(projected("2026-10-24", "1840.00"), projected("2026-10-12", "599.00", "Sample Mobile"), projected("2026-11-08", "1199.00", "Sample Fibre"))

    @Test
    fun `only bills expected inside the window are counted, sorted by day`() {
        val e = ProjectedBillsComponent(bills).estimate(DateRange(d("2026-10-12"), d("2026-10-31")))
        assertEquals(listOf("Sample Mobile", "Sample Power Co"), e.items.map { it.name })
    }

    @Test
    fun `the window's first and last days are inside it`() {
        val e = ProjectedBillsComponent(bills).estimate(DateRange(d("2026-10-24"), d("2026-11-08")))
        assertEquals(listOf("Sample Power Co", "Sample Fibre"), e.items.map { it.name })
    }

    @Test
    fun `the amount is the sum of the bills in the window`() {
        val e = ProjectedBillsComponent(bills).estimate(DateRange(d("2026-10-01"), d("2026-11-30")))
        assertEquals(BigDecimal("3638.00"), e.amount)
    }

    @Test
    fun `no window counts nothing`() {
        val e = ProjectedBillsComponent(bills).estimate(null)
        assertTrue(e.items.isEmpty())
        assertEquals(0, e.amount.signum())
    }

    @Test
    fun `a window with no bills in it counts nothing`() {
        val e = ProjectedBillsComponent(bills).estimate(DateRange(d("2026-10-25"), d("2026-11-07")))
        assertTrue(e.items.isEmpty())
        assertEquals(0, e.amount.signum())
    }
}
