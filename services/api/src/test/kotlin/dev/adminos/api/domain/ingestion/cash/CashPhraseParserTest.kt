package dev.adminos.api.domain.ingestion.cash

import dev.adminos.api.domain.financial.CategoryRule
import dev.adminos.api.domain.financial.InMemoryCategoryRuleRepository
import dev.adminos.api.domain.financial.RuleMatch
import dev.adminos.api.domain.financial.TransactionCategory
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

/** AOS-718: the rules read cash phrases deterministically, flag what they assumed, and refuse what they can't use. */
class CashPhraseParserTest {
    private val user = UUID.randomUUID()
    private val rules = InMemoryCategoryRuleRepository()
    private val parser = RuleCashPhraseParser(CashCategoryGuesser(rules))
    /** Saturday 17 Oct 2026. */
    private val today = LocalDate.of(2026, 10, 17)

    private fun parse(text: String, on: LocalDate = today, who: UUID = user) = runBlocking { parser.parse(text, PhraseContext(who, on)) }

    @Test
    fun `each entry keeps the text it came from, a note and where its category came from`() {
        val r = parse("200 chai and 150 auto")
        assertEquals(listOf("200 chai", "150 auto"), r.entries.map { it.evidence })
        assertEquals(listOf("Chai", "Auto"), r.entries.map { it.note })
        assertEquals(listOf(CategoryFrom.KEYWORD, CategoryFrom.KEYWORD), r.entries.map { it.categoryFrom })
        assertTrue(r.entries.all { it.warnings.isEmpty() && it.date == today })
    }

    @Test
    fun `filler words are left out of the note`() {
        assertEquals("Groceries", parse("paid 1.2k for groceries yesterday").entries.single().note)
        assertEquals("Ration", parse("2 hazar ka ration").entries.single().note)
    }

    @Test
    fun `an entry with no words has no note and asks for a category`() {
        val e = parse("paid 300").entries.single()
        assertNull(e.note)
        assertEquals(TransactionCategory.OTHER, e.category)
        assertEquals(setOf(DraftWarning.NO_NOTE, DraftWarning.CATEGORY_UNKNOWN), e.warnings)
    }

    @Test
    fun `kal and parso are read as the past and flagged`() {
        assertEquals(setOf(DraftWarning.DATE_ASSUMED), parse("kal 200 sabzi").entries.single().warnings)
        assertEquals(today.minusDays(2), parse("parso chai 20").entries.single().date)
    }

    @Test
    fun `a quantity and a price times a count are flagged`() {
        val q = parse("2 samosa 30").entries.single()
        assertEquals(BigDecimal("30"), q.amount)
        assertEquals("2 samosa", q.note)
        assertTrue(DraftWarning.QUANTITY_ASSUMED in q.warnings)
        val times = parse("chai 15 x 2").entries.single()
        assertEquals(0, BigDecimal("30").compareTo(times.amount))
        assertTrue(DraftWarning.QUANTITY_ASSUMED in times.warnings)
    }

    @Test
    fun `a date over a year ago is flagged`() {
        val e = parse("29 feb chai 20", on = LocalDate.of(2027, 3, 5)).entries.single()
        assertEquals(LocalDate.of(2024, 2, 29), e.date)
        assertTrue(DraftWarning.OLD_DATE in e.warnings)
    }

    @Test
    fun `the user's own correction for a payee wins`() = runBlocking<Unit> {
        rules.upsert(CategoryRule(userId = user, match = RuleMatch.MERCHANT, value = "raju", category = TransactionCategory.FOOD))
        val mine = parse("raju 500").entries.single()
        assertEquals(TransactionCategory.FOOD, mine.category)
        assertEquals(CategoryFrom.REMEMBERED, mine.categoryFrom)
        // Another user's correction is theirs alone.
        assertEquals(TransactionCategory.OTHER, parse("raju 500", who = UUID.randomUUID()).entries.single().category)
    }

    @Test
    fun `blank text gives nothing, and words without amounts are refused`() {
        assertEquals(PhraseParse.EMPTY, parse("   "))
        assertEquals(listOf(UnparsedReason.NO_AMOUNT), parse("just chai").unparsed.map { it.reason })
    }

    @Test
    fun `hostile text is read as text and never breaks the parser`() {
        val html = parse("<script>alert(1)</script> chai 50").entries.single()
        assertEquals(BigDecimal("50"), html.amount)
        assertTrue(html.note.orEmpty().none { it in "<>/()" }, "note: ${html.note}")
        val sql = parse("'; DROP TABLE transactions; -- 40")
        assertTrue(sql.entries.isEmpty())
        // Zero-width and right-to-left marks can't hide or reorder an amount.
        assertEquals(BigDecimal("40"), parse("chai ​4‍0‮").entries.single().amount)
        // Full-width digits read as digits.
        assertEquals(BigDecimal("40"), parse("chai ４０").entries.single().amount)
    }

    @Test
    fun `long or repetitive input finishes quickly`() {
        val inputs = listOf("1,".repeat(2_000), "a-".repeat(2_000), "9".repeat(4_000), "chai 40 and ".repeat(400), "₹".repeat(3_000) + "5", "x 2 ".repeat(1_000))
        for (text in inputs) {
            val took = measureTime { parse(text) }
            assertTrue(took.inWholeMilliseconds < 3_000, "${text.take(10)}… took $took")
        }
        assertEquals(listOf(UnparsedReason.TOO_LARGE), parse("9".repeat(400)).unparsed.map { it.reason })
    }

    @Test
    fun `a note is cut to the intake's limit`() {
        val e = parse("chai " + "masala ".repeat(60) + "40").entries.single()
        assertTrue((e.note?.length ?: 0) <= CashIntake.MAX_NOTE)
    }

    @Test
    fun `draft entries validate themselves`() {
        assertFailsWith<IllegalArgumentException> { DraftEntry(BigDecimal.ZERO, TransactionCategory.FOOD, null, today, "x", CategoryFrom.NONE) }
        assertFailsWith<IllegalArgumentException> { DraftEntry(BigDecimal("20000000"), TransactionCategory.FOOD, null, today, "x", CategoryFrom.NONE) }
        assertFailsWith<IllegalArgumentException> { DraftEntry(BigDecimal("1.234"), TransactionCategory.FOOD, null, today, "x", CategoryFrom.NONE) }
        assertFailsWith<IllegalArgumentException> { DraftEntry(BigDecimal("1"), TransactionCategory.FOOD, null, today, " ", CategoryFrom.NONE) }
    }

    // ── Text normalization and number words ──

    @Test
    fun `numbers are normalized, words become digits, and glued words are split`() {
        assertEquals("chai 40", PhraseText.normalize("Chai40"))
        assertEquals("rs 150 auto", PhraseText.normalize("Rs.150 auto"))
        assertEquals("fees 120000", PhraseText.normalize("fees 1,20,000"))
        assertEquals("200 for medicine", PhraseText.normalize("two hundred for medicine"))
        assertEquals("1500 shoes", PhraseText.normalize("one thousand five hundred shoes"))
        assertEquals("150 auto", PhraseText.normalize("dedh sau auto"))
        assertEquals("100000 rent", PhraseText.normalize("a lakh rent"))
        assertEquals("1.5 lakh", PhraseText.normalize("1.5 lakh"))
        assertEquals("do it", NumberWords.toDigits("do it"))
        assertEquals("12th oct", PhraseText.normalize("12th Oct"))
    }

    // ── Dates ──

    private fun date(text: String, on: LocalDate = today) = PhraseDates.extract(text, on).finding

    @Test
    fun `relative dates in English and Hinglish`() {
        assertEquals(DateFinding.On(today), date("aaj chai"))
        assertEquals(DateFinding.On(today.minusDays(1)), date("yesterday"))
        assertEquals(DateFinding.On(today.minusDays(1)), date("kal raat dinner"))
        assertEquals(DateFinding.On(today.minusDays(1), assumed = true), date("kal chai"))
        assertEquals(DateFinding.On(today.minusDays(2)), date("day before yesterday"))
        assertEquals(DateFinding.On(today.minusDays(5)), date("5 days ago"))
        assertEquals(DateFinding.Future, date("tomorrow"))
        assertNull(date("chai 40"))
    }

    @Test
    fun `weekdays look back and last means before today`() {
        assertEquals(DateFinding.On(LocalDate.of(2026, 10, 12)), date("monday"))
        assertEquals(DateFinding.On(today), date("saturday"))
        assertEquals(DateFinding.On(LocalDate.of(2026, 10, 10)), date("last sat"))
        assertEquals(DateFinding.On(LocalDate.of(2026, 10, 12)), date("pichle monday"))
    }

    @Test
    fun `written dates are day first, find the latest past one, and refuse the impossible`() {
        assertEquals(DateFinding.On(LocalDate.of(2026, 10, 5)), date("5/10"))
        assertEquals(DateFinding.On(LocalDate.of(2026, 10, 5)), date("oct 5"))
        assertEquals(DateFinding.On(LocalDate.of(2025, 12, 15)), date("15 dec"))
        assertEquals(DateFinding.Future, date("20 oct"))
        assertEquals(DateFinding.Future, date("20/10/2026"))
        assertEquals(DateFinding.On(LocalDate.of(2026, 1, 5)), date("5/1/26"))
        assertEquals(DateFinding.Invalid, date("31/02"))
        assertEquals(DateFinding.Invalid, date("12/13"))
        assertEquals(DateFinding.Invalid, date("30 feb 2026"))
        assertEquals(DateFinding.On(LocalDate.of(2028, 2, 29)), date("29 feb", LocalDate.of(2028, 3, 1)))
        assertEquals(DateFinding.On(LocalDate.of(2024, 2, 29)), date("29 feb", LocalDate.of(2026, 10, 17)))
    }

    @Test
    fun `the date phrase is taken out of the text`() {
        assertEquals(Dated("chai 40", DateFinding.On(LocalDate.of(2026, 10, 12))), PhraseDates.extract("chai 40 on 12 oct", today))
        assertTrue(PhraseDates.isOld(LocalDate.of(2025, 10, 15), today))
        assertTrue(!PhraseDates.isOld(LocalDate.of(2025, 10, 20), today))
    }
}
