package dev.adminos.api.domain.ask

import dev.adminos.api.domain.analytics.Bucket
import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.ask.AnswerGrounding.Verdict
import dev.adminos.api.domain.financial.TransactionCategory
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** AOS-712/710 (D-67): every number in an answer must be a number the tools returned. */
class AnswerGroundingTest {
    private val sep = DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
    private val oct = DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 18))
    private val zepto = AskToolResult.Merchant(
        AskToolCall.MerchantSpend(PeriodSpec.of(PeriodPreset.LAST_MONTH), MerchantQuery.of("Zepto")!!), sep,
        listOf(MerchantLine("Zepto", BigDecimal("123456.50"), 8, TransactionCategory.SHOPPING))
    )
    private val allowed = AnswerGrounding.allowed(listOf(zepto))

    private fun check(answer: String, a: AnswerGrounding.Allowed = allowed) = AnswerGrounding.check(answer, a)
    private val ungrounded = Verdict.Ungrounded(AnswerGrounding.UNGROUNDED_NUMBER)

    @Test
    fun `the same amount in any common format is grounded`() {
        for (answer in listOf(
            "You spent ₹1,23,456.50 at Zepto last month.", "Zepto: Rs 123456.5", "Rs.1,23,456.50 at Zepto",
            "INR 123,456.50", "123456.50 rupees", "₹१,२३,४५६.५०", "Zepto ko 123456.50/- gaye", "８ payments, ₹123456.50"
        )) assertEquals(Verdict.Grounded, check(answer), answer)
    }

    @Test
    fun `counts, dates of the period, ranks and words for small counts are grounded`() {
        assertEquals(Verdict.Grounded, check("8 payments between 1 September and 30 September 2026, Zepto was 1st"))
        assertEquals(Verdict.Grounded, check("eight payments? No, you made 8. Over the 30 days of September."))
        assertEquals(Verdict.Grounded, check("No numbers at all."))
    }

    @Test
    fun `a rounded, summed or made-up amount is ungrounded`() {
        assertEquals(ungrounded, check("About ₹1,23,000 at Zepto."))
        assertEquals(ungrounded, check("₹123457 at Zepto."))
        assertEquals(ungrounded, check("You spent ₹99999."))
        assertEquals(ungrounded, check("That is 12% of your income."))
        assertEquals(ungrounded, check("You made three payments."))
    }

    @Test
    fun `a count, a date or a rank written as money is ungrounded`() {
        assertEquals(ungrounded, check("You spent ₹8 at Zepto."))
        assertEquals(ungrounded, check("You spent Rs 30."))
        assertEquals(ungrounded, check("You spent 1 rupee."))
        assertEquals(Verdict.Grounded, check("You spent ₹1,23,456.50 in 8 payments."))
    }

    @Test
    fun `scale words and spelled-out large numbers are refused`() {
        val scaled = Verdict.Ungrounded(AnswerGrounding.SCALED_NUMBER)
        assertEquals(scaled, check("₹1.2L at Zepto"))
        assertEquals(scaled, check("₹1 lakh at Zepto"))
        assertEquals(scaled, check("about 123 thousand"))
        assertEquals(scaled, check("8k at Zepto"))
        assertEquals(scaled, check("2 crore"))
        val spelled = Verdict.Ungrounded(AnswerGrounding.SPELLED_NUMBER)
        assertEquals(spelled, check("about twelve hundred rupees"))
        assertEquals(spelled, check("one lakh twenty three thousand"))
        assertEquals(spelled, check("dedh hazaar"))
    }

    @Test
    fun `digits in a merchant name the model was shown are grounded and in a hidden one are not`() {
        val shown = AskToolResult.TopMerchants(AskToolCall.TopMerchants(PeriodSpec.of(PeriodPreset.THIS_MONTH), limit = 2), oct, listOf(
            MerchantLine("24x7 Mart", BigDecimal("410"), 2, TransactionCategory.SHOPPING),
            MerchantLine("Ravi 98000 00012", BigDecimal("500"), 1, TransactionCategory.OTHER)
        ))
        val a = AnswerGrounding.allowed(listOf(shown))
        assertEquals(Verdict.Grounded, check("24x7 Mart got ₹410 and a person got ₹500", a))
        assertEquals(ungrounded, check("Ravi on 98000 00012", a))
        assertEquals(listOf(BigDecimal("24"), BigDecimal("7")), AnswerGrounding.numbersIn("24x7 Mart"))
    }

    @Test
    fun `the server's comparison figures are grounded, including a fall`() {
        val now = AskToolResult.Summary(AskToolCall.SpendingSummary(PeriodSpec.of(PeriodPreset.THIS_MONTH)), oct, BigDecimal("2573"), BigDecimal.ZERO, 7)
        val before = AskToolResult.Summary(AskToolCall.SpendingSummary(PeriodSpec.of(PeriodPreset.LAST_MONTH)), sep, BigDecimal("4045"), BigDecimal.ZERO, 10)
        val a = AnswerGrounding.allowed(listOf(now, before))
        assertEquals(Verdict.Grounded, check("₹2,573 vs ₹4,045: ₹1,472 less, down 36.4%", a))
        assertEquals(ungrounded, check("down 36%", a))
        assertEquals(Verdict.Grounded, check("you received ₹0", a))
    }

    @Test
    fun `trend points and their dates are grounded`() {
        val trend = AskToolResult.Trend(AskToolCall.SpendingTrend(PeriodSpec.of(PeriodPreset.THIS_WEEK), Bucket.DAY), DateRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13)),
            listOf(TrendPoint(LocalDate.of(2026, 10, 12), BigDecimal("873")), TrendPoint(LocalDate.of(2026, 10, 13), BigDecimal("307.50"))))
        val a = AnswerGrounding.allowed(listOf(trend))
        assertEquals(Verdict.Grounded, check("Most on 12 October (₹873), then ₹307.50 on the 13th. ₹1,180.50 in all.", a))
        assertEquals(ungrounded, check("₹873 on 14 October", a))
    }

    @Test
    fun `a comparison needs the same tool and filters over different periods`() {
        val food = AskToolResult.Categories(AskToolCall.CategorySpend(PeriodSpec.of(PeriodPreset.THIS_MONTH), TransactionCategory.FOOD), oct, listOf(CategoryLine(TransactionCategory.FOOD, BigDecimal("5218.50"), 14)))
        val foodBefore = food.copy(call = food.call.copy(period = PeriodSpec.of(PeriodPreset.LAST_MONTH)), range = sep, rows = listOf(CategoryLine(TransactionCategory.FOOD, BigDecimal("8891"), 20)))
        val c = AskComparison.of(listOf(foodBefore, food)).single()
        assertEquals(1, c.later)
        assertEquals(0, c.earlier)
        assertEquals(BigDecimal("-3672.50"), c.difference)
        assertEquals(BigDecimal("-41.3"), c.changePct)
        assertEquals(emptyList(), AskComparison.of(listOf(food, zepto)), "different tools")
        assertEquals(emptyList(), AskComparison.of(listOf(food, food)), "same period")
        val top = AskToolResult.TopMerchants(AskToolCall.TopMerchants(PeriodSpec.of(PeriodPreset.THIS_MONTH)), oct, emptyList())
        assertEquals(emptyList(), AskComparison.of(listOf(top, top.copy(range = sep, call = top.call.copy(period = PeriodSpec.of(PeriodPreset.LAST_MONTH))))), "no single total")
        assertNull(AskComparison(0, 1, BigDecimal.TEN, BigDecimal.ZERO).changePct)
    }
}
