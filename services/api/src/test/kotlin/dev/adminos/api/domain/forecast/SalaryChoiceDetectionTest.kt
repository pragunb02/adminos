package dev.adminos.api.domain.forecast

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-722: the owner's confirmed or corrected salary drives detection; without one nothing changes. */
class SalaryChoiceDetectionTest {

    private val detector = SalaryDetector()
    private val at = Instant.parse("2026-10-18T05:00:00Z")
    private fun d(s: String) = LocalDate.parse(s)
    private fun credit(day: String, amount: String = "85000", payer: String? = "ACME TECHNOLOGIES PVT LTD") = IncomeCredit(d(day), payer, BigDecimal(amount))
    private fun choice(payer: String? = "ACME TECHNOLOGIES PVT LTD", day: Int = 1, amount: String = "85000.00", kind: SalaryChoiceKind = SalaryChoiceKind.CORRECTED) =
        SalaryChoice(payer, day, BigDecimal(amount), kind, at)

    private fun found(credits: List<IncomeCredit>, today: String, choice: SalaryChoice? = null) =
        assertIs<SalaryFinding.Found>(detector.detect(credits, d(today), choice))

    private fun notFound(credits: List<IncomeCredit>, today: String, choice: SalaryChoice? = null) =
        assertIs<SalaryFinding.NotFound>(detector.detect(credits, d(today), choice))

    private val firsts = listOf(credit("2026-07-01"), credit("2026-08-01"), credit("2026-09-01"), credit("2026-10-01"))

    // ── usualDay and source on a detected salary ──

    @Test
    fun `a detected salary on the 1st has usual day 1 and comes from detection`() {
        val s = found(firsts, "2026-10-17").salary
        assertEquals(1, s.usualDay)
        assertEquals(SalarySource.DETECTED, s.source)
    }

    @Test
    fun `the usual day is the nominal one before a weekend move`() {
        // 7 Aug 2027 is a Saturday: the salary is expected Monday the 9th, but the usual day is still the 7th.
        val s = found(listOf(credit("2027-01-07"), credit("2027-02-08"), credit("2027-03-08"), credit("2027-04-07"), credit("2027-05-07"),
            credit("2027-06-07"), credit("2027-07-07")), "2027-07-20").salary
        assertEquals(d("2027-08-09"), s.expectedOn)
        assertEquals(7, s.usualDay)
    }

    @Test
    fun `a month-end salary's usual day is that month's last day`() {
        assertEquals(31, found(listOf(credit("2026-06-30"), credit("2026-08-01"), credit("2026-08-31"), credit("2026-09-30")), "2026-10-17").salary.usualDay)
        // Paid the Friday before a weekend month end: 30 Sep 2028 is a Saturday, expected on the 29th, usually the 30th.
        val credits = listOf(credit("2027-12-31"), credit("2028-01-31"), credit("2028-02-29"), credit("2028-03-31"), credit("2028-04-28"),
            credit("2028-05-31"), credit("2028-06-30"), credit("2028-07-31"), credit("2028-08-31"))
        val s = found(credits, "2028-09-10").salary
        assertEquals(d("2028-09-29"), s.expectedOn)
        assertEquals(30, s.usualDay)
    }

    @Test
    fun `without a choice detection is unchanged`() {
        assertEquals(detector.detect(firsts, d("2026-10-17")), detector.detect(firsts, d("2026-10-17"), null))
    }

    // ── with the owner's choice ──

    @Test
    fun `a confirmed salary keeps the detected payer, day and amount and says it is confirmed`() {
        val f = found(firsts, "2026-10-17", choice(kind = SalaryChoiceKind.CONFIRMED))
        assertEquals("ACME TECHNOLOGIES PVT LTD", f.salary.payer)
        assertEquals(d("2026-11-01"), f.salary.expectedOn)
        assertEquals(BigDecimal("85000.00"), f.salary.typicalAmount)
        assertEquals(d("2026-10-01"), f.salary.lastReceivedOn)
        assertEquals(4, f.salary.credits)
        assertEquals(1, f.salary.usualDay)
        assertEquals(SalarySource.CONFIRMED, f.salary.source)
        assertFalse(f.late)
    }

    @Test
    fun `of two salaries the choice picks which payer is the salary`() {
        val second = listOf("2026-07-15", "2026-08-15", "2026-09-15", "2026-10-15").map { credit(it, "40000", "Globex Freelance") }
        assertEquals("ACME TECHNOLOGIES PVT LTD", found(firsts + second, "2026-10-17").salary.payer, "detection alone takes the larger")
        val s = found(firsts + second, "2026-10-17", choice("Globex Freelance", day = 15, amount = "40000")).salary
        assertEquals("Globex Freelance", s.payer)
        assertEquals(d("2026-11-15"), s.expectedOn)
        assertEquals(BigDecimal("40000.00"), s.typicalAmount)
        assertEquals(d("2026-10-15"), s.lastReceivedOn)
    }

    @Test
    fun `a corrected day moves the expected payday at once`() {
        val s = found(firsts, "2026-10-17", choice(day = 5)).salary
        assertEquals(d("2026-11-05"), s.expectedOn)
        assertEquals(5, s.usualDay)
    }

    @Test
    fun `a corrected amount is the typical amount, the last credit stays what came`() {
        val s = found(firsts, "2026-10-17", choice(amount = "95000")).salary
        assertEquals(BigDecimal("95000.00"), s.typicalAmount)
        assertEquals(BigDecimal("85000"), s.lastAmount)
    }

    @Test
    fun `the payer is matched ignoring case and punctuation`() {
        val s = found(firsts, "2026-10-17", choice("acme technologies pvt. ltd.")).salary
        assertEquals("acme technologies pvt. ltd.", s.payer, "the owner's spelling is shown")
        assertEquals(4, s.credits)
    }

    @Test
    fun `the chosen payer matches a narration that adds words, as whole words only`() {
        val narrated = listOf("2026-08-01" to "ACME SALARY AUG", "2026-09-01" to "ACME SALARY SEP", "2026-10-01" to "NEFT-ACME-SALARY-OCT")
            .map { (day, payer) -> credit(day, payer = payer) }
        assertEquals(3, found(narrated, "2026-10-17", choice("Acme")).salary.credits)
        assertEquals(1, found(narrated, "2026-10-17", choice("NEFT ACME SALARY OCT HDFC")).salary.credits, "a longer choice contains only October's words")
        assertEquals(NoSalaryReason.NOT_SEEN, notFound(listOf(credit("2026-10-01", payer = "ACMECORP")), "2026-10-17", choice("Acme")).reason)
        assertEquals(NoSalaryReason.NOT_SEEN, notFound(narrated, "2026-10-17", choice("Acm")).reason)
        assertEquals(NoSalaryReason.NOT_SEEN, notFound(narrated, "2026-10-17", choice("Salary Acme")).reason, "words in another order")
    }

    @Test
    fun `payers in other scripts match by their own letters`() {
        val tcs = listOf("2026-08-01", "2026-09-01", "2026-10-01").map { credit(it, payer = "टाटा कंसल्टेंसी वेतन") }
        assertEquals(3, found(tcs, "2026-10-17", choice("टाटा कंसल्टेंसी")).salary.credits)
        // Different Devanagari names don't collapse into one key (ASCII-only keys would make both empty).
        assertEquals(NoSalaryReason.NOT_SEEN, notFound(tcs, "2026-10-17", choice("इन्फोसिस")).reason)
    }

    @Test
    fun `a named choice never matches credits without a name`() {
        val unnamed = listOf(credit("2026-09-01", payer = null), credit("2026-10-01", payer = "--"))
        assertEquals(NoSalaryReason.NOT_SEEN, notFound(unnamed, "2026-10-17", choice("ACME")).reason)
    }

    @Test
    fun `an employer's small refund is not the salary`() {
        val s = found(firsts + credit("2026-10-20", "2000"), "2026-10-21", choice()).salary
        assertEquals(d("2026-10-01"), s.lastReceivedOn)
        assertEquals(4, s.credits)
    }

    @Test
    fun `one credit a month counts once even with a second payment days later`() {
        val s = found(firsts + credit("2026-10-05", "60000"), "2026-10-17", choice()).salary
        assertEquals(4, s.credits)
        assertEquals(d("2026-10-01"), s.lastReceivedOn, "the largest of a cluster is the salary")
    }

    @Test
    fun `the weekend habit is learnt for the chosen day`() {
        // 7 Feb and 7 Mar 2027 are Sundays, paid on the Monday; 7 Aug 2027 is a Saturday.
        val credits = listOf(credit("2027-01-07"), credit("2027-02-08"), credit("2027-03-08"), credit("2027-04-07"), credit("2027-05-07"),
            credit("2027-06-07"), credit("2027-07-07"))
        assertEquals(d("2027-08-09"), found(credits, "2027-07-20", choice(day = 7)).salary.expectedOn)
    }

    @Test
    fun `day 31 is the month's last day in a short month, moved like the payer moves it`() {
        // 31 Oct 2026 is a Saturday, paid Friday the 30th; 30 Nov 2026 is a Monday.
        val credits = listOf(credit("2026-07-31"), credit("2026-08-31"), credit("2026-09-30"), credit("2026-10-30"))
        val s = found(credits, "2026-11-02", choice(day = 31)).salary
        assertEquals(d("2026-11-30"), s.expectedOn)
        assertEquals(30, s.usualDay)
    }

    @Test
    fun `a single credit is enough once the owner has said it is the salary`() {
        val s = found(listOf(credit("2026-10-01")), "2026-10-17", choice()).salary
        assertEquals(1, s.credits)
        assertEquals(d("2026-11-01"), s.expectedOn)
    }

    @Test
    fun `a chosen salary past its day is late, and stopped after the grace days`() {
        val three = firsts.take(3)
        assertTrue(found(three, "2026-10-05", choice()).late)
        val stopped = notFound(three, "2026-10-20", choice())
        assertEquals(NoSalaryReason.STOPPED, stopped.reason)
        assertEquals("ACME TECHNOLOGIES PVT LTD", stopped.payer)
        assertEquals(d("2026-09-01"), stopped.lastSeen)
    }

    @Test
    fun `a chosen payer with no salary-sized credit is not seen`() {
        val n = notFound(firsts, "2026-10-17", choice("Globex Corp"))
        assertEquals(NoSalaryReason.NOT_SEEN, n.reason)
        assertEquals("Globex Corp", n.payer)
        assertNull(n.lastSeen)
        assertEquals(NoSalaryReason.NOT_SEEN, notFound(firsts, "2026-10-17", choice(amount = "200000")).reason, "every credit is under half the amount")
        assertEquals(NoSalaryReason.NOT_SEEN, notFound(emptyList(), "2026-10-17", choice()).reason)
    }

    @Test
    fun `credits after today are not counted`() {
        assertEquals(NoSalaryReason.NOT_SEEN, notFound(listOf(credit("2026-10-20")), "2026-10-17", choice()).reason)
    }

    @Test
    fun `no payer matches only credits without a name`() {
        val unnamed = listOf(credit("2026-08-01", payer = null), credit("2026-09-01", payer = "  "), credit("2026-10-01", payer = null))
        val s = found(unnamed + credit("2026-10-10", payer = "ACME"), "2026-10-17", choice(payer = null)).salary
        assertNull(s.payer)
        assertEquals(3, s.credits)
        assertEquals(d("2026-10-01"), s.lastReceivedOn)
        assertEquals(NoSalaryReason.NOT_SEEN, notFound(firsts, "2026-10-17", choice(payer = null)).reason)
    }

    // ── the choice validates itself ──

    @Test
    fun `a choice refuses days outside 1 to 31`() {
        assertFailsWith<IllegalArgumentException> { choice(day = 0) }
        assertFailsWith<IllegalArgumentException> { choice(day = 32) }
        assertFailsWith<IllegalArgumentException> { choice(day = -1) }
    }

    @Test
    fun `a choice refuses zero, negative, sub-paisa and huge amounts`() {
        listOf("0", "0.00", "-1", "12.345", "1000000000.01", "1E+12").forEach { a ->
            assertFailsWith<IllegalArgumentException>(a) { choice(amount = a) }
        }
        assertEquals(BigDecimal("1000000000.00"), choice(amount = "1000000000.00").amount)
        assertEquals(BigDecimal("0.01"), choice(amount = "0.01").amount)
    }

    @Test
    fun `a choice refuses blank, padded, overlong and invisible-character payers`() {
        val hostile = listOf("", " ", " ACME", "ACME ", "A".repeat(101), "ACME\nLTD", "ACME\u0000", "\u202EEMCA", "AC\u200BME", "ACME\u2028LTD",
            "\uD800ACME", "ACME\uFEFF", "AC\u2066ME", "ACME\u0085")
        hostile.forEach { p -> assertFailsWith<IllegalArgumentException>(p.map { it.code }.toString()) { choice(payer = p) } }
    }

    @Test
    fun `a choice keeps hostile-looking but printable payers as plain text`() {
        listOf("'; DROP TABLE salary_choices; --", "<script>alert(1)</script>", "टाटा कंसल्टेंसी", "A".repeat(100), "Café & Co. 😀").forEach { p ->
            assertEquals(p, choice(payer = p).payer)
        }
    }

    @Test
    fun `of trims the payer and keeps two decimal places`() {
        val c = SalaryChoice.of("  ACME  ", 1, BigDecimal("85000"), SalaryChoiceKind.CORRECTED, at)
        assertEquals("ACME", c.payer)
        assertEquals("85000.00", c.amount.toPlainString())
        assertEquals("85000.50", SalaryChoice.of(null, 1, BigDecimal("85000.500"), SalaryChoiceKind.CORRECTED, at).amount.toPlainString())
        assertFailsWith<IllegalArgumentException> { SalaryChoice.of("   ", 1, BigDecimal("1"), SalaryChoiceKind.CORRECTED, at) }
    }

    @Test
    fun `a choice never prints its payer or amount`() {
        val text = choice(payer = "ACME SECRET EMPLOYER", amount = "123456.78").toString()
        assertFalse("ACME" in text || "123456" in text, text)
    }
}
