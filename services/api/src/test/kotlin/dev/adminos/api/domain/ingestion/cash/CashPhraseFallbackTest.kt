package dev.adminos.api.domain.ingestion.cash

import dev.adminos.api.domain.financial.CategoryRule
import dev.adminos.api.domain.financial.InMemoryCategoryRuleRepository
import dev.adminos.api.domain.financial.MerchantCatalogue
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

/** AOS-718: the AI extension point fills only what the rules left open, grounded in the text; and the category sources. */
class CashPhraseFallbackTest {
    private val user = UUID.randomUUID()
    private val today = LocalDate.of(2026, 10, 17)
    private val ctx = PhraseContext(user, today)
    private val rules = RuleCashPhraseParser(CashCategoryGuesser(InMemoryCategoryRuleRepository()))

    /** A stand-in for a model: answers every segment with these entries. */
    private class Fake(private val answer: (String) -> List<DraftEntry>) : CashPhraseParser {
        val asked = mutableListOf<String>()
        override suspend fun parse(text: String, context: PhraseContext): PhraseParse { asked += text; return PhraseParse(answer(text), emptyList()) }
    }

    private fun entry(amount: String, category: TransactionCategory, evidence: String = "x") =
        DraftEntry(BigDecimal(amount), category, "Note", today, evidence, CategoryFrom.KEYWORD)

    @Test
    fun `what the rules decided is never sent or changed`() = runBlocking<Unit> {
        val fake = Fake { listOf(entry("200", TransactionCategory.HEALTH)) }
        val r = FallbackCashPhraseParser(rules, fake).parse("200 chai", ctx)
        assertEquals(TransactionCategory.FOOD, r.entries.single().category)
        assertTrue(fake.asked.isEmpty())
    }

    @Test
    fun `a segment without an amount may be read by the fallback when its amount is in the text`() = runBlocking<Unit> {
        val fake = Fake { text -> if ("teen" in text) listOf(entry("300", TransactionCategory.TRANSPORT)) else emptyList() }
        // "teen ... sau" isn't next to each other, so the rules see no amount; the model's 300 must be written in the text: it isn't.
        val rejected = FallbackCashPhraseParser(rules, fake).parse("teen auto wala sau", ctx)
        assertTrue(rejected.entries.isEmpty())
        assertEquals(UnparsedReason.NO_AMOUNT, rejected.unparsed.single().reason)
        val grounded = Fake { listOf(entry("1200", TransactionCategory.SHOPPING)) }
        val kept = FallbackCashPhraseParser(rules, grounded).parse("rs 1.2k worth of kirana stuff", ctx)
        // The rules read this one themselves (1.2k), so the fallback isn't asked.
        assertEquals(CategoryFrom.KEYWORD, kept.entries.single().categoryFrom)
        // "9:30" is a time to the rules, so "chai 9:30" has no amount; a reading of 30 is in the text, so it's kept, marked as the fallback's.
        val read = FallbackCashPhraseParser(rules, Fake { listOf(entry("30", TransactionCategory.FOOD)) }).parse("chai 9:30", ctx)
        val e = read.entries.single()
        assertEquals(CategoryFrom.FALLBACK, e.categoryFrom)
        assertEquals("chai 9:30", e.evidence)
        assertTrue(read.unparsed.isEmpty())
    }

    @Test
    fun `a fallback entry for an amount not in the text, or a transfer, is rejected`() = runBlocking<Unit> {
        val invented = Fake { listOf(entry("999", TransactionCategory.FOOD), entry("1", TransactionCategory.TRANSFER)) }
        val r = FallbackCashPhraseParser(rules, invented).parse("chai wala bhaiya", ctx)
        assertTrue(r.entries.isEmpty())
        assertEquals(listOf(UnparsedReason.NO_AMOUNT), r.unparsed.map { it.reason })
    }

    @Test
    fun `refusals other than a missing amount stay refused`() = runBlocking<Unit> {
        val fake = Fake { listOf(entry("40", TransactionCategory.FOOD)) }
        val r = FallbackCashPhraseParser(rules, fake).parse("chai 40 tomorrow", ctx)
        assertTrue(r.entries.isEmpty())
        assertEquals(UnparsedReason.FUTURE_DATE, r.unparsed.single().reason)
        assertTrue(fake.asked.isEmpty())
    }

    @Test
    fun `an uncategorised entry may get a category for the same amount and date, never Other or Transfer`() = runBlocking<Unit> {
        val good = FallbackCashPhraseParser(rules, Fake { listOf(entry("500", TransactionCategory.HEALTH)) }).parse("raju 500", ctx).entries.single()
        assertEquals(TransactionCategory.HEALTH, good.category)
        assertEquals(CategoryFrom.FALLBACK, good.categoryFrom)
        assertTrue(DraftWarning.CATEGORY_UNKNOWN !in good.warnings)
        for (bad in listOf(entry("500", TransactionCategory.TRANSFER), entry("500", TransactionCategory.OTHER), entry("501", TransactionCategory.HEALTH))) {
            val kept = FallbackCashPhraseParser(rules, Fake { listOf(bad) }).parse("raju 500", ctx).entries.single()
            assertEquals(TransactionCategory.OTHER, kept.category)
            assertEquals(CategoryFrom.NONE, kept.categoryFrom)
        }
    }

    @Test
    fun `grounding accepts the amount as written, with paise or as k`() {
        assertTrue(FallbackCashPhraseParser.grounded(entry("40", TransactionCategory.FOOD), "chai 40"))
        assertTrue(FallbackCashPhraseParser.grounded(entry("40.50", TransactionCategory.FOOD), "chai rs 40.50"))
        assertTrue(FallbackCashPhraseParser.grounded(entry("1200", TransactionCategory.FOOD), "kirana 1.2k"))
        assertTrue(!FallbackCashPhraseParser.grounded(entry("40", TransactionCategory.FOOD), "chai 400"))
        assertTrue(!FallbackCashPhraseParser.grounded(entry("40", TransactionCategory.FOOD), "chai 140"))
    }

    // ── Category sources ──

    @Test
    fun `category sources in order, and cash is never a transfer`() = runBlocking<Unit> {
        val repo = InMemoryCategoryRuleRepository()
        val guesser = CashCategoryGuesser(repo)
        assertEquals(TransactionCategory.FOOD to CategoryFrom.CATALOGUE, guesser.guess(user, "Swiggy order"))
        assertEquals(TransactionCategory.FOOD to CategoryFrom.KEYWORD, guesser.guess(user, "chai via gpay"))
        assertEquals(TransactionCategory.FOOD to CategoryFrom.PATTERN, guesser.guess(user, "Iyengar bakery"))
        assertEquals(TransactionCategory.OTHER to CategoryFrom.NONE, guesser.guess(user, "Raju"))
        assertEquals(TransactionCategory.OTHER to CategoryFrom.NONE, guesser.guess(user, null))
        assertEquals(TransactionCategory.OTHER to CategoryFrom.NONE, guesser.guess(user, "atm"))
        repo.upsert(CategoryRule(userId = user, match = RuleMatch.MERCHANT, value = "swiggy", category = TransactionCategory.TRANSFER))
        assertEquals(TransactionCategory.OTHER to CategoryFrom.NONE, guesser.guess(user, "swiggy"))
        assertEquals(TransactionCategory.FOOD to CategoryFrom.CATALOGUE, guesser.guess(UUID.randomUUID(), "swiggy"))
    }

    @Test
    fun `keywords match whole words, the longest phrase first`() {
        val k = CashKeywords.DEFAULT
        assertEquals(TransactionCategory.FOOD, k.match("pani puri"))
        assertEquals(TransactionCategory.UTILITIES, k.match("pani"))
        assertEquals(TransactionCategory.ENTERTAINMENT, k.match("movie ticket"))
        assertNull(k.match("autograph"))
        assertNull(k.match("   "))
        assertEquals(listOf("t", "shirt"), CashKeywords.wordsOf("T-Shirt"))
    }

    @Test
    fun `a bad keyword file fails on load`() {
        fun file(rules: String) = """{"version":"t","rules":[$rules]}"""
        assertFailsWith<IllegalArgumentException> { CashKeywords.parse(file("""{"category":"nosuch","words":["a1"]}""")) }
        assertFailsWith<IllegalArgumentException> { CashKeywords.parse(file("""{"category":"transfer","words":["atm"]}""")) }
        assertFailsWith<IllegalArgumentException> { CashKeywords.parse(file("""{"category":"food","words":[]}""")) }
        assertFailsWith<IllegalArgumentException> { CashKeywords.parse(file("""{"category":"food","words":["x"]}""")) }
        assertFailsWith<IllegalArgumentException> { CashKeywords.parse(file("""{"category":"food","words":["chai"]},{"category":"health","words":["Chai"]}""")) }
        assertFailsWith<IllegalStateException> { CashKeywords.load("/cash/missing.json") }
        assertEquals("t", CashKeywords.parse(file("""{"category":"food","words":["chai"]}""")).version)
        assertTrue(MerchantCatalogue.DEFAULT.merchants.isNotEmpty())
    }
}
