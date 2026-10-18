package dev.adminos.api.domain.forecast

import dev.adminos.api.domain.financial.BillType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-721: regular monthly bills that haven't arrived yet are projected from the user's own history, once. */
class MonthlyBillProjectorTest {

    private val projector = MonthlyBillProjector()
    private fun d(s: String) = LocalDate.parse(s)
    private fun ym(s: String) = YearMonth.parse(s)

    private fun bill(due: String, amount: String = "1840", biller: String = "Sample Power Co", type: BillType = BillType.ELECTRICITY, account: String? = null) =
        PastBill(type, biller, account, d(due), BigDecimal(amount))

    /** Sample Power Co, due on the 24th, June to September 2026. */
    private val fourMonths = listOf(bill("2026-06-24", "2100"), bill("2026-07-24", "1840"), bill("2026-08-24", "1650"), bill("2026-09-24", "1920"))

    @Test
    fun `a regular electricity biller not yet billed this month is projected with its median amount and usual day`() {
        // given four months running, due on the 24th, nothing yet in October
        // when projected on 18 October
        val projected = projector.project(fourMonths, d("2026-10-18"))
        // then October's bill is expected on the 24th at the median of July to September
        assertEquals(1, projected.size)
        val p = projected.single()
        assertEquals(BillType.ELECTRICITY, p.type)
        assertEquals("Sample Power Co", p.name)
        assertEquals(ym("2026-10"), p.month)
        assertEquals(d("2026-10-24"), p.expectedOn)
        assertEquals(BigDecimal("1840.00"), p.amount)
        assertEquals(4, p.monthsSeen)
        assertEquals(ym("2026-06"), p.seenFrom)
        assertEquals(ym("2026-09"), p.seenTo)
    }

    @Test
    fun `an upcoming bill for this month means it has arrived and is not projected`() {
        val projected = projector.project(fourMonths + bill("2026-10-24", "1700"), d("2026-10-18"))
        assertTrue(projected.none { it.month == ym("2026-10") })
    }

    @Test
    fun `a bill paid early this month counts as arrived`() {
        // given this month's bill, due later, already in the history (status doesn't matter to the projector)
        val projected = projector.project(fourMonths + bill("2026-10-26", "1900"), d("2026-10-05"))
        assertTrue(projected.none { it.month == ym("2026-10") })
    }

    @Test
    fun `a cancelled bill this month also counts as arrived`() {
        // given the caller passes cancelled bills too (any status), October's cancelled bill is present
        val projected = projector.project(fourMonths + bill("2026-10-24", "1840"), d("2026-10-10"))
        assertTrue(projected.none { it.month == ym("2026-10") })
    }

    @Test
    fun `only two months of history projects nothing`() {
        val projected = projector.project(listOf(bill("2026-08-24"), bill("2026-09-24")), d("2026-10-18"))
        assertTrue(projected.isEmpty())
    }

    @Test
    fun `a gap in the last three months projects nothing`() {
        // given June, July and September but no August
        val projected = projector.project(listOf(bill("2026-06-24"), bill("2026-07-24"), bill("2026-09-24")), d("2026-10-18"))
        assertTrue(projected.isEmpty())
    }

    @Test
    fun `a quarterly biller is never projected`() {
        val water = listOf("2025-10-20", "2026-01-20", "2026-04-20", "2026-07-20").map { bill(it, "950", "Sample Water Board", BillType.WATER) }
        assertTrue(projector.project(water, d("2026-10-05")).isEmpty())
    }

    @Test
    fun `an annual biller is never projected`() {
        val gas = listOf("2024-10-28", "2025-10-28").map { bill(it, "2400", "Sample Gas Co", BillType.GAS) }
        assertTrue(projector.project(gas, d("2026-10-05")).isEmpty())
    }

    @Test
    fun `a usual day that has passed with no bill is not projected`() {
        // given due on the 24th and no October bill, on the 25th
        assertTrue(projector.project(fourMonths, d("2026-10-25")).none { it.month == ym("2026-10") })
    }

    @Test
    fun `a usual day that is today with no bill is not projected`() {
        assertTrue(projector.project(fourMonths, d("2026-10-24")).none { it.month == ym("2026-10") })
    }

    @Test
    fun `a usual 31st is the 30th in a 30-day month`() {
        // given due on the 31st, the 30th (September is short) and the 31st, so the usual day is the 31st
        val bills = listOf(bill("2026-08-31"), bill("2026-09-30"), bill("2026-10-31"))
        // when November, a 30-day month, is projected
        val p = projector.project(bills, d("2026-11-05")).single()
        assertEquals(d("2026-11-30"), p.expectedOn)
    }

    @Test
    fun `a usual 31st is the 28th in February`() {
        val bills = listOf(bill("2026-11-30"), bill("2026-12-31"), bill("2027-01-31"))
        val p = projector.project(bills, d("2027-02-10")).single()
        assertEquals(d("2027-02-28"), p.expectedOn)
    }

    @Test
    fun `next month is projected when this month is billed and the run is at least three`() {
        // given July to September and October's bill already arrived
        val bills = listOf(bill("2026-07-24", "1800"), bill("2026-08-24", "1900"), bill("2026-09-24", "2000"), bill("2026-10-22", "1700"))
        val projected = projector.project(bills, d("2026-10-25"))
        // then November is expected, from August to October
        val p = projected.single()
        assertEquals(ym("2026-11"), p.month)
        assertEquals(d("2026-11-24"), p.expectedOn)
        assertEquals(BigDecimal("1900.00"), p.amount)
        assertEquals(4, p.monthsSeen)
        assertEquals(ym("2026-10"), p.seenTo)
    }

    @Test
    fun `next month is not projected when this month is not billed`() {
        assertTrue(projector.project(fourMonths, d("2026-10-18")).none { it.month == ym("2026-11") })
    }

    @Test
    fun `credit-card and pay-later bills are ignored`() {
        val cards = listOf("2026-07-15", "2026-08-15", "2026-09-15").map { bill(it, "42000", "Sample Bank Card", BillType.CREDIT_CARD) }
        val payLater = listOf("2026-07-20", "2026-08-20", "2026-09-20").map { bill(it, "3200", "Sample PayLater", BillType.PAY_LATER) }
        assertTrue(projector.project(cards + payLater, d("2026-10-05")).isEmpty())
    }

    @Test
    fun `zero and negative amounts are ignored`() {
        val bills = listOf(bill("2026-07-24", "0"), bill("2026-08-24", "-50"), bill("2026-09-24", "1840"))
        assertTrue(projector.project(bills, d("2026-10-05")).isEmpty())
    }

    @Test
    fun `two accounts at one biller are projected separately`() {
        val a = listOf("2026-07-10", "2026-08-10", "2026-09-10").map { bill(it, "599", "Sample Mobile", BillType.MOBILE, "98xxxx1234") }
        val b = listOf("2026-07-12", "2026-08-12", "2026-09-12").map { bill(it, "399", "Sample Mobile", BillType.MOBILE, "98xxxx5678") }
        val projected = projector.project(a + b, d("2026-10-05"))
        assertEquals(listOf(BigDecimal("599.00"), BigDecimal("399.00")), projected.map { it.amount })
        assertEquals(listOf(d("2026-10-10"), d("2026-10-12")), projected.map { it.expectedOn })
    }

    @Test
    fun `biller name variations in case and punctuation group together`() {
        val bills = listOf(bill("2026-07-24", biller = "SAMPLE POWER CO."), bill("2026-08-24", biller = "sample  power-co"), bill("2026-09-24", biller = " Sample Power Co "))
        val p = projector.project(bills, d("2026-10-05")).single()
        assertEquals("Sample Power Co", p.name) // the newest bill's name, trimmed
    }

    @Test
    fun `the same biller name under two types is two billers`() {
        val power = listOf("2026-07-24", "2026-08-24", "2026-09-24").map { bill(it, biller = "Sample Utilities") }
        val gas = listOf("2026-07-24", "2026-08-24").map { bill(it, "700", "Sample Utilities", BillType.GAS) }
        val p = projector.project(power + gas, d("2026-10-05")).single()
        assertEquals(BillType.ELECTRICITY, p.type)
    }

    @Test
    fun `two bills in one month count as one month and the largest is used`() {
        // given August has a bill and its correction
        val bills = listOf(bill("2026-07-24", "1800"), bill("2026-08-24", "900"), bill("2026-08-26", "2400"), bill("2026-09-24", "2000"))
        val p = projector.project(bills, d("2026-10-05")).single()
        // then the months are 1800, 2400, 2000, so the median is 2000 and the run is three months
        assertEquals(BigDecimal("2000.00"), p.amount)
        assertEquals(3, p.monthsSeen)
    }

    @Test
    fun `a seasonal amount uses the median of the last three months`() {
        // given summer air-conditioning bills, falling into autumn
        val bills = listOf(bill("2026-05-24", "4100"), bill("2026-06-24", "4800"), bill("2026-07-24", "3900"), bill("2026-08-24", "2650"), bill("2026-09-24", "3120.50"))
        val p = projector.project(bills, d("2026-10-05")).single()
        assertEquals(BigDecimal("3120.50"), p.amount)
        assertEquals(5, p.monthsSeen)
    }

    @Test
    fun `the usual day is the median day of the last three bills`() {
        val bills = listOf(bill("2026-07-20"), bill("2026-08-27"), bill("2026-09-22"))
        assertEquals(d("2026-10-22"), projector.project(bills, d("2026-10-05")).single().expectedOn)
    }

    @Test
    fun `the run works across the year boundary`() {
        val bills = listOf(bill("2026-10-08", "1199", "Sample Fibre", BillType.INTERNET), bill("2026-11-08", "1199", "Sample Fibre", BillType.INTERNET),
            bill("2026-12-08", "1199", "Sample Fibre", BillType.INTERNET))
        val p = projector.project(bills, d("2027-01-03")).single()
        assertEquals(ym("2027-01"), p.month)
        assertEquals(d("2027-01-08"), p.expectedOn)
        assertEquals(ym("2026-10"), p.seenFrom)
        assertEquals(ym("2026-12"), p.seenTo)
    }

    @Test
    fun `next month across the year boundary is projected in January`() {
        val bills = listOf("2026-10-08", "2026-11-08", "2026-12-08").map { bill(it, "1199", "Sample Fibre", BillType.INTERNET) }
        val p = projector.project(bills, d("2026-12-20")).single()
        assertEquals(d("2027-01-08"), p.expectedOn)
    }

    @Test
    fun `a bill without a name gets a readable label from its type`() {
        val bills = listOf("2026-07-24", "2026-08-24", "2026-09-24").map { bill(it, biller = "   ") }
        assertEquals("Electricity", projector.project(bills, d("2026-10-05")).single().name)
    }

    @Test
    fun `projections are sorted by expected day then name`() {
        val power = listOf("2026-07-24", "2026-08-24", "2026-09-24").map { bill(it, biller = "Zeta Power") }
        val mobile = listOf("2026-07-24", "2026-08-24", "2026-09-24").map { bill(it, "599", "alpha Mobile", BillType.MOBILE) }
        val fibre = listOf("2026-07-12", "2026-08-12", "2026-09-12").map { bill(it, "999", "Sample Fibre", BillType.INTERNET) }
        val projected = projector.project(power + mobile + fibre, d("2026-10-05"))
        assertEquals(listOf("Sample Fibre", "alpha Mobile", "Zeta Power"), projected.map { it.name })
    }

    @Test
    fun `no bills projects nothing`() {
        assertTrue(projector.project(emptyList(), d("2026-10-05")).isEmpty())
    }

    @Test
    fun `hostile biller names are carried as data`() {
        // given names with markup, an injection and unicode bidi overrides
        val names = listOf("<script>alert(1)</script>", "Power'; DROP TABLE bills;--", "\u202EoC rewoP elpmaS\u202C", "बिजली विभाग")
        val bills = names.flatMap { n -> listOf("2026-07-24", "2026-08-24", "2026-09-24").map { bill(it, biller = n) } }
        val projected = projector.project(bills, d("2026-10-05"))
        // then each is projected under its own name, unchanged
        assertEquals(names.toSet(), projected.map { it.name }.toSet())
    }

    @Test
    fun `names in another script are told apart`() {
        val a = listOf("2026-07-24", "2026-08-24", "2026-09-24").map { bill(it, "1000", "बिजली विभाग") }
        val b = listOf("2026-07-24", "2026-08-24", "2026-09-24").map { bill(it, "2000", "जल विभाग") }
        assertEquals(2, projector.project(a + b, d("2026-10-05")).size)
    }

    @Test
    fun `toString never shows the amount or the biller`() {
        val p = projector.project(fourMonths, d("2026-10-18")).single()
        val texts = listOf(p.toString(), fourMonths.first().toString())
        texts.forEach { t ->
            assertFalse(t.contains("Sample Power"), t)
            assertFalse(t.contains("1840"), t)
            assertFalse(t.contains("2100"), t)
        }
    }

    // ── Only utilities, and never counted twice ──

    @Test
    fun `rent, EMI, insurance and other bills are not projected`() {
        val types = listOf(BillType.RENT, BillType.EMI, BillType.INSURANCE, BillType.OTHER)
        val bills = types.flatMap { t -> listOf("2026-07-05", "2026-08-05", "2026-09-05").map { bill(it, "25000", "Sample ${t.name}", t) } }
        assertTrue(projector.project(bills, d("2026-10-01")).isEmpty())
    }

    @Test
    fun `every projected type is a monthly utility`() {
        assertEquals(setOf(BillType.ELECTRICITY, BillType.MOBILE, BillType.INTERNET, BillType.WATER, BillType.GAS), MonthlyBillProjector.PROJECTED_TYPES)
    }

    @Test
    fun `a same-type bill under another name this month counts as arrived`() {
        // given the biller's name was read differently this month
        val projected = projector.project(fourMonths + bill("2026-10-24", "1790", "SPC Electricity Dept", account = "CA-0099"), d("2026-10-18"))
        assertTrue(projected.isEmpty())
    }

    @Test
    fun `a same-type bill with a zero amount this month counts as arrived`() {
        assertTrue(projector.project(fourMonths + bill("2026-10-24", "0"), d("2026-10-18")).isEmpty())
    }

    @Test
    fun `a bill of another type this month does not count as arrived`() {
        val projected = projector.project(fourMonths + bill("2026-10-15", "599", "Sample Mobile", BillType.MOBILE), d("2026-10-18"))
        assertEquals(ym("2026-10"), projected.single().month)
    }

    @Test
    fun `a bill due on the 1st of next month near a projected 30th counts as arrived`() {
        // given a biller due on the 30th, and October's bill read as due on 1 November
        val bills = listOf(bill("2026-07-30"), bill("2026-08-30"), bill("2026-09-30"), bill("2026-11-01"))
        // then October's 30th isn't projected, nor November (October wasn't billed)
        assertTrue(projector.project(bills, d("2026-10-10")).isEmpty())
    }

    @Test
    fun `a bill that drifted into the neighbouring month is not projected twice`() {
        // given August's bill read as due on 1 September, so September has two bills and August none
        val bills = listOf(bill("2026-06-30"), bill("2026-07-30"), bill("2026-09-01"), bill("2026-09-30"))
        // then the run has a hole and nothing is projected (the safe side)
        assertTrue(projector.project(bills, d("2026-10-10")).isEmpty())
    }

    @Test
    fun `one account's arrived bill hides the other account's projection`() {
        // given two phones at one operator and the first phone's October bill already in (an accepted under-count)
        val a = listOf("2026-07-10", "2026-08-10", "2026-09-10", "2026-10-10").map { bill(it, "599", "Sample Mobile", BillType.MOBILE, "98xxxx1234") }
        val b = listOf("2026-07-25", "2026-08-25", "2026-09-25").map { bill(it, "399", "Sample Mobile", BillType.MOBILE, "98xxxx5678") }
        val projected = projector.project(a + b, d("2026-10-12"))
        // then only the first phone's November bill is expected
        assertEquals(listOf(ym("2026-11") to BigDecimal("599.00")), projected.map { it.month to it.amount })
    }
}
