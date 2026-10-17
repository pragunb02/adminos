package dev.adminos.api.domain.forecast

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-714: the salary is learnt from history, deterministically, and says why when it can't be. */
class SalaryDetectorTest {

    private val detector = SalaryDetector()
    private fun d(s: String) = LocalDate.parse(s)
    private fun credit(day: String, amount: String = "85000", payer: String? = "ACME TECHNOLOGIES PVT LTD") = IncomeCredit(d(day), payer, BigDecimal(amount))

    private fun found(credits: List<IncomeCredit>, today: String): SalaryFinding.Found =
        assertIs<SalaryFinding.Found>(detector.detect(credits, d(today)))

    private fun notFound(credits: List<IncomeCredit>, today: String): SalaryFinding.NotFound =
        assertIs<SalaryFinding.NotFound>(detector.detect(credits, d(today)))

    @Test
    fun `a salary on the 1st every month is expected on the next 1st`() {
        val f = found(listOf(credit("2026-07-01"), credit("2026-08-01"), credit("2026-09-01"), credit("2026-10-01")), "2026-10-17")
        assertEquals(d("2026-11-01"), f.salary.expectedOn) // 1 Nov 2026 is a Sunday; no weekend habit seen, so not moved
        assertEquals(BigDecimal("85000.00"), f.salary.typicalAmount)
        assertEquals(d("2026-10-01"), f.salary.lastReceivedOn)
        assertEquals(4, f.salary.credits)
        assertEquals("ACME TECHNOLOGIES PVT LTD", f.salary.payer)
        assertFalse(f.late)
    }

    @Test
    fun `the last working day is read from the month's end, across February in a leap year`() {
        // 29 Feb 2028 (Tue) is the last day; 31 Mar 2028 is a Friday; 30 Apr 2028 is a Sunday, paid Friday 28 Apr.
        val credits = listOf(credit("2027-12-31"), credit("2028-01-31"), credit("2028-02-29"), credit("2028-03-31"), credit("2028-04-28"))
        val f = found(credits, "2028-05-10")
        assertEquals(d("2028-05-31"), f.salary.expectedOn)
        // The Friday-before habit is learnt: 30 Jun 2028 is a Friday, but 30 Sep 2028 (Saturday) moves to the 29th.
        val sept = found(credits + listOf(credit("2028-05-31"), credit("2028-06-30"), credit("2028-07-31"), credit("2028-08-31")), "2028-09-10")
        assertEquals(d("2028-09-29"), sept.salary.expectedOn)
    }

    @Test
    fun `a salary paid on the Monday after a weekend 7th is expected on that Monday`() {
        // 7 Feb and 7 Mar 2027 are Sundays (paid on the 8th); 7 Aug 2027 is a Saturday.
        val f = found(listOf(credit("2027-01-07"), credit("2027-02-08"), credit("2027-03-08"), credit("2027-04-07"), credit("2027-05-07"),
            credit("2027-06-07"), credit("2027-07-07")), "2027-07-20")
        assertEquals(d("2027-08-09"), f.salary.expectedOn)
    }

    @Test
    fun `the 30th, 31st and 1st read as one month-end pattern`() {
        val f = found(listOf(credit("2026-06-30"), credit("2026-08-01"), credit("2026-08-31"), credit("2026-09-30")), "2026-10-17")
        assertEquals(d("2026-10-31"), f.salary.expectedOn)
    }

    @Test
    fun `a mid-month salary on the 16th stays a plain day`() {
        val f = found(listOf(credit("2026-07-16"), credit("2026-08-17"), credit("2026-09-16")), "2026-10-01")
        assertEquals(d("2026-10-16"), f.salary.expectedOn)
    }

    @Test
    fun `fewer than three monthly credits is no salary`() {
        assertEquals(NoSalaryReason.NO_REGULAR_CREDIT, notFound(listOf(credit("2026-09-01"), credit("2026-10-01")), "2026-10-17").reason)
        assertEquals(NoSalaryReason.NO_REGULAR_CREDIT, notFound(emptyList(), "2026-10-17").reason)
    }

    @Test
    fun `weekly payouts are not a monthly salary`() {
        val weekly = (0..10).map { credit(LocalDate.parse("2026-08-01").plusWeeks(it.toLong()).toString(), "9000") }
        assertEquals(NoSalaryReason.NO_REGULAR_CREDIT, notFound(weekly, "2026-10-17").reason)
    }

    @Test
    fun `small monthly credits like interest are not a salary`() {
        val interest = listOf("2026-07-01", "2026-08-01", "2026-09-01", "2026-10-01").map { credit(it, "312.40", "SAVINGS INTEREST") }
        assertEquals(NoSalaryReason.NO_REGULAR_CREDIT, notFound(interest, "2026-10-17").reason)
    }

    @Test
    fun `amounts all over the place are named as the reason`() {
        val f = notFound(listOf(credit("2026-07-01", "20000"), credit("2026-08-01", "85000"), credit("2026-09-01", "140000")), "2026-10-17")
        assertEquals(NoSalaryReason.AMOUNT_VARIES, f.reason)
        assertEquals(d("2026-09-01"), f.lastSeen)
    }

    @Test
    fun `one bonus month among three still counts`() {
        val f = found(listOf(credit("2026-08-01"), credit("2026-09-01", "170000"), credit("2026-10-01")), "2026-10-17")
        assertEquals(BigDecimal("85000.00"), f.salary.typicalAmount)
    }

    @Test
    fun `days of the month that wander are named as the reason`() {
        val f = notFound(listOf(credit("2026-07-01"), credit("2026-08-06"), credit("2026-09-10")), "2026-10-01")
        assertEquals(NoSalaryReason.DATE_VARIES, f.reason)
    }

    @Test
    fun `a salary a few days late is late, and long overdue is stopped`() {
        val credits = listOf(credit("2026-06-01"), credit("2026-07-01"), credit("2026-08-01"), credit("2026-09-01"))
        val late = found(credits, "2026-10-05")
        assertTrue(late.late)
        assertEquals(d("2026-10-01"), late.salary.expectedOn)
        val stopped = notFound(credits, "2026-10-20")
        assertEquals(NoSalaryReason.STOPPED, stopped.reason)
        assertEquals(d("2026-09-01"), stopped.lastSeen)
    }

    @Test
    fun `a reimbursement near payday is ignored and a monthly one doesn't win`() {
        val credits = listOf("2026-07-01", "2026-08-01", "2026-09-01", "2026-10-01").flatMap { listOf(credit(it)) } +
            listOf("2026-07-04", "2026-08-03").map { credit(it, "2400") } +
            listOf("2026-07-17", "2026-08-17", "2026-09-17", "2026-10-16").map { credit(it, "6000") }
        val f = found(credits, "2026-10-17")
        assertEquals(BigDecimal("85000.00"), f.salary.typicalAmount)
        assertEquals(d("2026-11-01"), f.salary.expectedOn)
    }

    @Test
    fun `of two salaries the larger wins, and payer names match loosely`() {
        val credits = listOf("2026-07-05", "2026-08-05", "2026-09-05").map { credit(it, "30000", "Side Gig LLP") } +
            listOf(credit("2026-07-31", payer = "ACME Technologies"), credit("2026-08-31", payer = "ACME  TECHNOLOGIES"), credit("2026-09-30", payer = "acme technologies."))
        val f = found(credits, "2026-10-10")
        assertEquals(BigDecimal("85000.00"), f.salary.typicalAmount)
        assertEquals("acme technologies.", f.salary.payer)
    }

    @Test
    fun `credits without a payer name can still be a salary`() {
        val f = found(listOf(credit("2026-08-07", payer = null), credit("2026-09-07", payer = null), credit("2026-10-07", payer = null)), "2026-10-17")
        assertNull(f.salary.payer)
        // 7 Nov 2026 is a Saturday, but no weekend habit was seen, so the day isn't moved.
        assertEquals(d("2026-11-07"), f.salary.expectedOn)
    }

    @Test
    fun `credits after today are not seen`() {
        val credits = listOf(credit("2026-08-01"), credit("2026-09-01"), credit("2026-10-01"), credit("2026-11-01"))
        assertEquals(d("2026-10-01"), found(credits, "2026-10-17").salary.lastReceivedOn)
    }

    @Test
    fun `the reason a regular payer failed beats the plain no-salary reason`() {
        val credits = listOf(credit("2026-07-01", "20000", "A"), credit("2026-08-01", "85000", "A"), credit("2026-09-01", "140000", "A"),
            credit("2026-09-10", "500", "B"))
        assertEquals(NoSalaryReason.AMOUNT_VARIES, notFound(credits, "2026-10-10").reason)
    }

    @Test
    fun `payer keys ignore case and punctuation`() {
        assertEquals("acme tech pvt ltd", SalaryDetector.payerKey("ACME-TECH, Pvt. Ltd."))
        assertEquals("", SalaryDetector.payerKey(null))
    }
}
