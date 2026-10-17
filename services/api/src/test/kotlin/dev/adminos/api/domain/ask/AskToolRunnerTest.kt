package dev.adminos.api.domain.ask

import dev.adminos.api.domain.analytics.AnalyticsService
import dev.adminos.api.domain.analytics.Bucket
import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.analytics.InMemoryAnalyticsRepository
import dev.adminos.api.domain.ask.eval.AskSeed
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.TransactionCategory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** AOS-712: the read-only tools on the seeded synthetic DB give the console's numbers, for the scoped user only. */
class AskToolRunnerTest {
    private val txns = InMemoryTransactionRepository().also { r -> runBlocking { AskSeed.transactions().forEach { r.save(it) } } }
    private val analytics = AnalyticsService(InMemoryAnalyticsRepository(txns), AskSeed.CLOCK)
    private val runner = AskToolRunner(analytics)
    private val owner = AskScope(AskSeed.OWNER, AskSeed.ZONE, AskSeed.TODAY)
    private val oct = DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 18))
    private val thisMonth = PeriodSpec.of(PeriodPreset.THIS_MONTH)

    private fun run(call: AskToolCall, scope: AskScope = owner): AskToolResult = runBlocking {
        when (val o = runner.run(scope, call)) {
            is AskToolOutcome.Ran -> o.result
            is AskToolOutcome.Refused -> throw AssertionError("refused: ${o.code}")
        }
    }

    private fun refused(call: AskToolCall) = runBlocking { (runner.run(owner, call) as AskToolOutcome.Refused).code }
    private fun merchant(name: String, period: PeriodSpec = thisMonth) = run(AskToolCall.MerchantSpend(period, MerchantQuery.of(name)!!)) as AskToolResult.Merchant

    @Test
    fun `the summary equals the console's summary for the same period`() = runBlocking<Unit> {
        val r = run(AskToolCall.SpendingSummary(thisMonth)) as AskToolResult.Summary
        val console = analytics.summary(AskSeed.OWNER, oct, AskSeed.ZONE)
        assertEquals(oct, r.range)
        assertEquals(console.current.spent, r.spent)
        assertEquals(console.current.received, r.received)
        assertEquals(console.spendCount, r.payments)
        assertEquals(0, BigDecimal("150000").compareTo(r.received), "salary is income; transfers and the card bill are not")
    }

    @Test
    fun `another user's money never appears`() {
        val zepto = merchant("Zepto", PeriodSpec.of(PeriodPreset.TODAY))
        assertEquals(emptyList(), zepto.rows, "the stranger's Zepto order today is not the owner's")
        val stranger = AskScope(AskSeed.STRANGER, AskSeed.ZONE, AskSeed.TODAY)
        val theirs = run(AskToolCall.MerchantSpend(PeriodSpec.of(PeriodPreset.TODAY), MerchantQuery.of("Zepto")!!), stranger) as AskToolResult.Merchant
        assertEquals(0, BigDecimal("77777").compareTo(theirs.total))
        val top = run(AskToolCall.TopMerchants(PeriodSpec.of(PeriodPreset.THIS_YEAR), limit = 10)) as AskToolResult.TopMerchants
        assertTrue(top.rows.none { it.spent >= BigDecimal("33333") })
    }

    @Test
    fun `an exact merchant name wins over names that contain it`() {
        assertEquals(listOf("Swiggy"), merchant("swiggy").rows.map { it.name })
        assertEquals(listOf("Swiggy Instamart"), merchant("Instamart").rows.map { it.name })
        assertEquals(emptyList(), merchant("Instamartt").rows)
        assertEquals(0, BigDecimal.ZERO.compareTo(merchant("Nobody Here").total))
    }

    @Test
    fun `refunds reduce a merchant's spending and reversals cancel out`() {
        val amazon = merchant("Amazon")
        val gross = txns.let { runBlocking { it.findByUserId(AskSeed.OWNER, from = oct.from.atStartOfDay(AskSeed.ZONE).toInstant(), to = AskSeed.CLOCK.instant(), limit = 10_000) } }
            .filter { it.merchantName == "Amazon" && it.nature == dev.adminos.api.domain.financial.TransactionNature.SPEND }
            .fold(BigDecimal.ZERO) { a, t -> a + t.amount }
        assertEquals(0, (gross - BigDecimal("300")).compareTo(amazon.total), "the 14 October refund is netted")
        val flipkart = merchant("Flipkart", PeriodSpec.range(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 7)))
        assertEquals(emptyList(), flipkart.rows, "a reversal pair is neither spend nor refund")
    }

    @Test
    fun `a late-night payment counts on its day in the user's zone`() {
        val yesterday = merchant("Swiggy", PeriodSpec.of(PeriodPreset.YESTERDAY))
        assertTrue(yesterday.rows.single().payments >= 1)
        assertTrue(yesterday.range == DateRange(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 17)))
    }

    @Test
    fun `a category filter gives exactly that category, with zero when there is none`() {
        val food = run(AskToolCall.CategorySpend(PeriodSpec.of(PeriodPreset.TODAY), TransactionCategory.FOOD)) as AskToolResult.Categories
        assertEquals(listOf(CategoryLine(TransactionCategory.FOOD, BigDecimal.ZERO, 0)), food.rows)
        val all = run(AskToolCall.CategorySpend(thisMonth)) as AskToolResult.Categories
        val summary = run(AskToolCall.SpendingSummary(thisMonth)) as AskToolResult.Summary
        assertEquals(0, summary.spent.compareTo(all.total), "categories add up to the total (D-46)")
        assertFalse(all.rows.any { it.category == TransactionCategory.TRANSFER })
    }

    @Test
    fun `top merchants respect the limit and the category`() {
        val top = run(AskToolCall.TopMerchants(thisMonth, TransactionCategory.FOOD, 2)) as AskToolResult.TopMerchants
        assertEquals(2, top.rows.size)
        assertTrue(top.rows.all { it.category == TransactionCategory.FOOD })
        assertTrue(top.rows[0].spent >= top.rows[1].spent)
        assertEquals(null, top.total)
    }

    @Test
    fun `a trend adds up to the summary and refuses too many points`() {
        val trend = run(AskToolCall.SpendingTrend(thisMonth, Bucket.WEEK)) as AskToolResult.Trend
        val summary = run(AskToolCall.SpendingSummary(thisMonth)) as AskToolResult.Summary
        assertEquals(0, summary.spent.compareTo(trend.total))
        assertEquals(LocalDate.of(2026, 9, 28), trend.points.first().start, "weeks start on Monday")
        assertEquals(AskToolRunner.TOO_MANY_POINTS, refused(AskToolCall.SpendingTrend(PeriodSpec.of(PeriodPreset.THIS_YEAR), Bucket.DAY)))
        assertIs<AskToolOutcome.Ran>(runBlocking { runner.run(owner, AskToolCall.SpendingTrend(PeriodSpec.of(PeriodPreset.THIS_YEAR), Bucket.MONTH)) })
        assertIs<AskToolOutcome.Ran>(runBlocking { runner.run(owner, AskToolCall.SpendingTrend(PeriodSpec.of(PeriodPreset.THIS_YEAR), Bucket.WEEK)) })
    }

    @Test
    fun `future and overlong periods are refused without running`() {
        assertEquals(AskToolRunner.FUTURE_PERIOD, refused(AskToolCall.SpendingSummary(PeriodSpec.month(YearMonth.of(2026, 12)))))
        assertEquals(AskToolRunner.PERIOD_TOO_LONG, refused(AskToolCall.SpendingSummary(PeriodSpec.range(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 1, 1)))))
    }

    @Test
    fun `the model sees aggregates and safe names only`() {
        val top = run(AskToolCall.TopMerchants(PeriodSpec.range(LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 16)), TransactionCategory.OTHER, 10)) as AskToolResult.TopMerchants
        assertEquals(4, top.rows.size, "the four hostile names are rows the tool returns")
        val wire = AskResultWire.forModel(listOf(top))
        val text = wire.toString()
        val names = wire["results"]!!.jsonArray.single().jsonObject["rows"]!!.jsonArray.map { it.jsonObject["merchant"]!!.jsonPrimitive.content }.toSet()
        assertTrue(AskSeed.PHONE_NAME !in text && "98000" !in text, "a name with a phone number is hidden")
        assertTrue(AskSeed.INVENTED_FIGURES_NAME !in text && "99999" !in text, "a name with many digits is hidden")
        assertTrue(AskResultWire.HIDDEN_NAME in names)
        assertTrue(AskSeed.INJECTION_NAME in names, "an instruction without digits is shown as data; the number check and the vocabulary contain it")
        assertTrue(AskSeed.STRANGER.toString() !in text && AskSeed.OWNER.toString() !in text, "no user ids")
        val result = wire["results"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("id", "tool", "period", "category", "limit", "rows"), result.keys)
        assertEquals("t1", result["id"]!!.jsonPrimitive.content)
        val bidi = run(AskToolCall.MerchantSpend(PeriodSpec.range(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 2)), MerchantQuery.of("Chai Point")!!)) as AskToolResult.Merchant
        assertEquals(listOf(AskSeed.BIDI_NAME), bidi.rows.map { it.name }, "the user's own rows are matched by words")
        assertTrue('\u202E' !in AskResultWire.forModel(listOf(bidi)).toString(), "hidden characters never reach the model")
    }

    @Test
    fun `amounts are shown in rupees with Indian grouping and comparisons are labelled by id`() {
        val now = run(AskToolCall.SpendingSummary(thisMonth))
        val before = run(AskToolCall.SpendingSummary(PeriodSpec.of(PeriodPreset.LAST_MONTH)))
        val wire = AskResultWire.forModel(listOf(now, before))
        val first = wire["results"]!!.jsonArray[0].jsonObject
        assertEquals("₹1,50,000", first["received"]!!.jsonPrimitive.content)
        val comparison = wire["comparisons"]!!.jsonArray.single() as JsonObject
        assertEquals("t1", comparison["later"]!!.jsonPrimitive.content)
        assertEquals("t2", comparison["earlier"]!!.jsonPrimitive.content)
        assertTrue(comparison["changePct"]!!.jsonPrimitive.content.endsWith("%"))
    }
}
