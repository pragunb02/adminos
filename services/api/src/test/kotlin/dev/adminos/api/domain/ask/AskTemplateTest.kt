package dev.adminos.api.domain.ask

import dev.adminos.api.domain.analytics.Bucket
import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionCategory.FOOD
import dev.adminos.api.domain.financial.TransactionCategory.SHOPPING
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AOS-710: answers written by code from the results, always grounded. */
class AskTemplateTest {
    private val oct = DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 18))
    private val sep = DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
    private val day = DateRange(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 17))
    private val thisMonth = PeriodSpec.of(PeriodPreset.THIS_MONTH)
    private val lastMonth = PeriodSpec.of(PeriodPreset.LAST_MONTH)

    private fun grounded(results: List<AskToolResult>): String {
        val text = AskTemplate.render(results)
        assertEquals(AnswerGrounding.Verdict.Grounded, AnswerGrounding.check(text, AnswerGrounding.allowed(results)), text)
        return text
    }

    @Test
    fun `a summary and a single day`() {
        assertEquals("On 17 Oct 2026 you spent ₹1,391 in 4 payments and received ₹0.",
            grounded(listOf(AskToolResult.Summary(AskToolCall.SpendingSummary(PeriodSpec.of(PeriodPreset.YESTERDAY)), day, BigDecimal("1391"), BigDecimal.ZERO, 4))))
        assertTrue("1 payment and" in grounded(listOf(AskToolResult.Summary(AskToolCall.SpendingSummary(thisMonth), oct, BigDecimal("103"), BigDecimal.ZERO, 1))))
    }

    @Test
    fun `a comparison says more or less, with the percent when there is one`() {
        fun food(p: PeriodSpec, r: DateRange, spent: String) = AskToolResult.Categories(AskToolCall.CategorySpend(p, FOOD), r, listOf(CategoryLine(FOOD, BigDecimal(spent), 3)))
        val less = grounded(listOf(food(thisMonth, oct, "5218.50"), food(lastMonth, sep, "8891")))
        assertTrue("₹3,672.50 less than from 1 Sep 2026 to 30 Sep 2026 (41.3% less)" in less, less)
        val more = grounded(listOf(food(lastMonth, sep, "100"), food(thisMonth, oct, "150")))
        assertTrue("₹50 more than" in more && "(50.0% more)" in more, more)
        val fromNothing = grounded(listOf(food(thisMonth, oct, "150"), food(lastMonth, sep, "0")))
        assertTrue("₹150 more than" in fromNothing && "%" !in fromNothing, fromNothing)
    }

    @Test
    fun `categories list the top five and how many more, or say there was nothing`() {
        val rows = TransactionCategory.entries.take(7).mapIndexed { i, c -> CategoryLine(c, BigDecimal(700 - i * 10), 1) }
        val text = grounded(listOf(AskToolResult.Categories(AskToolCall.CategorySpend(thisMonth), oct, rows)))
        assertTrue("and 2 more; ₹4,690 in all." in text, text)
        assertEquals("From 1 Oct 2026 to 18 Oct 2026 there was no spending.", grounded(listOf(AskToolResult.Categories(AskToolCall.CategorySpend(thisMonth), oct, emptyList()))))
        assertTrue(grounded(listOf(AskToolResult.Categories(AskToolCall.CategorySpend(thisMonth, FOOD), oct, listOf(CategoryLine(FOOD, BigDecimal.ZERO, 0))))).startsWith("Food from"))
    }

    @Test
    fun `merchants, top merchants and their empty cases`() {
        val zepto = AskToolCall.MerchantSpend(lastMonth, MerchantQuery.of("Zepto")!!)
        assertEquals("No spending at Zepto from 1 Sep 2026 to 30 Sep 2026.", grounded(listOf(AskToolResult.Merchant(zepto, sep, emptyList()))))
        assertTrue("Zepto from 1 Sep 2026 to 30 Sep 2026: ₹3,792 in 8 payments." in grounded(listOf(AskToolResult.Merchant(zepto, sep, listOf(MerchantLine("Zepto", BigDecimal("3792"), 8, SHOPPING))))))
        val top = AskToolCall.TopMerchants(thisMonth, FOOD, 2)
        assertTrue("1. Swiggy ₹2,573, 2. Zomato ₹1,200." in grounded(listOf(AskToolResult.TopMerchants(top, oct, listOf(MerchantLine("Swiggy", BigDecimal("2573"), 7, FOOD), MerchantLine("Zomato", BigDecimal("1200"), 3, FOOD))))))
        assertEquals("From 1 Oct 2026 to 18 Oct 2026 there was no spending on food.", grounded(listOf(AskToolResult.TopMerchants(top, oct, emptyList()))))
        assertEquals("From 1 Oct 2026 to 18 Oct 2026 there was no spending.", grounded(listOf(AskToolResult.TopMerchants(AskToolCall.TopMerchants(thisMonth), oct, emptyList()))))
    }

    @Test
    fun `a trend names its highest bucket, or says there was nothing`() {
        val call = AskToolCall.SpendingTrend(thisMonth, Bucket.WEEK)
        val points = listOf(TrendPoint(LocalDate.of(2026, 9, 28), BigDecimal("900")), TrendPoint(LocalDate.of(2026, 10, 5), BigDecimal("1200.50")))
        assertTrue("₹2,100.50 in all; the most in the week of 5 Oct 2026 (₹1,200.50)." in grounded(listOf(AskToolResult.Trend(call, oct, points))))
        assertEquals("From 1 Oct 2026 to 18 Oct 2026 there was no spending.", grounded(listOf(AskToolResult.Trend(call, oct, listOf(TrendPoint(oct.from, BigDecimal.ZERO))))))
        assertEquals("From 1 Oct 2026 to 18 Oct 2026 there was no spending.", grounded(listOf(AskToolResult.Trend(call, oct, emptyList()))))
    }

    @Test
    fun `every decline reason has plain words`() {
        for (r in DeclineReason.entries) assertTrue(AskTemplate.decline(r).length in 20..200)
    }
}
