package dev.adminos.api.domain.ask

import dev.adminos.api.domain.ai.AiReading
import dev.adminos.api.domain.ai.AiReasonCode
import dev.adminos.api.domain.analytics.Bucket
import dev.adminos.api.domain.financial.TransactionCategory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** AOS-712/710: a model's plan is read strictly into the typed vocabulary; anything else is rejected with a code. */
class AskPlanWireTest {
    private val lastMonth = PeriodSpec.of(PeriodPreset.LAST_MONTH)

    private fun valid(text: String): AskPlan = when (val r = AskPlanWire.read(text)) {
        is AiReading.Valid -> r.value
        is AiReading.Invalid -> throw AssertionError("rejected: ${r.code}")
    }

    private fun code(text: String): AiReasonCode = when (val r = AskPlanWire.read(text)) {
        is AiReading.Valid -> throw AssertionError("accepted: ${r.value}")
        is AiReading.Invalid -> r.code
    }

    private fun period(preset: String = "last_month", month: String? = null, from: String? = null, to: String? = null) =
        """{"preset":"$preset","month":${month?.let { "\"$it\"" } ?: "null"},"from":${from?.let { "\"$it\"" } ?: "null"},"to":${to?.let { "\"$it\"" } ?: "null"}}"""

    private fun call(tool: String, period: String = period(), category: String = "null", merchant: String = "null", bucket: String = "null", limit: String = "null") =
        """{"tool":"$tool","period":$period,"category":$category,"merchant":$merchant,"bucket":$bucket,"limit":$limit}"""

    private fun tools(vararg calls: String) = """{"action":"tools","reason":null,"calls":[${calls.joinToString(",")}]}"""

    @Test
    fun `each tool reads into its typed call`() {
        assertEquals(AskPlan.Tools(listOf(AskToolCall.SpendingSummary(lastMonth))), valid(tools(call("spending_summary"))))
        assertEquals(AskPlan.Tools(listOf(AskToolCall.CategorySpend(lastMonth, TransactionCategory.FOOD))), valid(tools(call("category_spend", category = "\"food\""))))
        assertEquals(AskPlan.Tools(listOf(AskToolCall.CategorySpend(lastMonth, null))), valid(tools(call("category_spend"))))
        assertEquals(AskPlan.Tools(listOf(AskToolCall.MerchantSpend(lastMonth, MerchantQuery.of("Zepto")!!))), valid(tools(call("merchant_spend", merchant = "\"  zepto \""))))
        assertEquals(AskPlan.Tools(listOf(AskToolCall.TopMerchants(lastMonth, null, 3))), valid(tools(call("top_merchants", limit = "3"))))
        assertEquals(AskPlan.Tools(listOf(AskToolCall.TopMerchants(lastMonth, TransactionCategory.SHOPPING, 5))), valid(tools(call("top_merchants", category = "\"shopping\""))))
        assertEquals(AskPlan.Tools(listOf(AskToolCall.SpendingTrend(lastMonth, Bucket.WEEK, null))), valid(tools(call("spending_trend", bucket = "\"week\""))))
    }

    @Test
    fun `months and ranges read as dates`() {
        val month = valid(tools(call("spending_summary", period("month", month = "2026-08")))) as AskPlan.Tools
        assertEquals(PeriodSpec.month(YearMonth.of(2026, 8)), month.calls.single().period)
        val range = valid(tools(call("spending_summary", period("range", from = "2026-10-01", to = "2026-10-10")))) as AskPlan.Tools
        assertEquals(PeriodSpec.range(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10)), range.calls.single().period)
    }

    @Test
    fun `a decline reads with its reason and no calls`() {
        assertEquals(AskPlan.Decline(DeclineReason.OTHER_PEOPLE), valid("""{"action":"decline","reason":"other_people","calls":[]}"""))
        assertEquals(AskPlanWire.DECLINE_WITH_CALLS, code("""{"action":"decline","reason":"future","calls":[${call("spending_summary")}]}"""))
        assertEquals(AskPlanWire.BAD_DECLINE_REASON, code("""{"action":"decline","reason":"bored","calls":[]}"""))
        assertEquals(AskPlanWire.BAD_DECLINE_REASON, code("""{"action":"tools","reason":"future","calls":[${call("spending_summary")}]}"""))
    }

    @Test
    fun `an extra field anywhere is rejected so a model cannot set the user`() {
        assertEquals(AskPlanWire.UNEXPECTED_FIELDS, code("""{"action":"tools","reason":null,"calls":[${call("spending_summary")}],"userId":"00000000-0000-4000-8000-000000000713"}"""))
        val withUser = call("spending_summary").dropLast(1) + ""","userId":"x"}"""
        assertEquals(AskPlanWire.UNEXPECTED_FIELDS, code(tools(withUser)))
        val withZone = call("spending_summary", period = period().dropLast(1) + ""","zone":"UTC"}""")
        assertEquals(AskPlanWire.UNEXPECTED_FIELDS, code(tools(withZone)))
        assertEquals(AskPlanWire.UNEXPECTED_FIELDS, code("""{"action":"tools","calls":[]}"""))
    }

    @Test
    fun `unknown tools and too many or duplicate calls are rejected`() {
        assertEquals(AskPlanWire.UNKNOWN_TOOL, code(tools(call("sql_query"))))
        assertEquals(AskPlanWire.UNKNOWN_TOOL, code(tools(call("SPENDING_SUMMARY"))))
        assertEquals(AskPlanWire.TOO_MANY_CALLS, code(tools(call("spending_summary"), call("category_spend"), call("top_merchants"), call("spending_summary", period("today")))))
        assertEquals(AskPlanWire.DUPLICATE_CALLS, code(tools(call("spending_summary"), call("spending_summary"))))
        assertEquals(AskPlanWire.NO_CALLS, code(tools()))
        assertEquals(AskPlanWire.BAD_CALL, code("""{"action":"tools","reason":null,"calls":["spending_summary"]}"""))
        assertEquals(AskPlanWire.BAD_CALL, code("""{"action":"tools","reason":null,"calls":{}}"""))
        assertEquals(AskPlanWire.BAD_ACTION, code("""{"action":"run","reason":null,"calls":[]}"""))
        assertEquals(AskPlanWire.BAD_ACTION, code("""{"action":1,"reason":null,"calls":[]}"""))
    }

    @Test
    fun `a tool's unused arguments must be null`() {
        assertEquals(AskPlanWire.ARGUMENT_NOT_ALLOWED, code(tools(call("spending_summary", category = "\"food\""))))
        assertEquals(AskPlanWire.ARGUMENT_NOT_ALLOWED, code(tools(call("merchant_spend", merchant = "\"Zepto\"", limit = "3"))))
        assertEquals(AskPlanWire.ARGUMENT_NOT_ALLOWED, code(tools(call("category_spend", bucket = "\"day\""))))
        assertEquals(AskPlanWire.ARGUMENT_NOT_ALLOWED, code(tools(call("spending_trend", bucket = "\"day\"", merchant = "\"Zepto\""))))
        assertEquals(AskPlanWire.BAD_MERCHANT, code(tools(call("merchant_spend"))))
        assertEquals(AskPlanWire.BAD_BUCKET, code(tools(call("spending_trend"))))
    }

    @Test
    fun `bad values are rejected with their own code`() {
        assertEquals(AskPlanWire.BAD_CATEGORY, code(tools(call("category_spend", category = "\"FOOD\""))))
        assertEquals(AskPlanWire.BAD_CATEGORY, code(tools(call("category_spend", category = "7"))))
        assertEquals(AskPlanWire.BAD_BUCKET, code(tools(call("spending_trend", bucket = "\"monthly\""))))
        assertEquals(AskPlanWire.BAD_LIMIT, code(tools(call("top_merchants", limit = "0"))))
        assertEquals(AskPlanWire.BAD_LIMIT, code(tools(call("top_merchants", limit = "1000"))))
        assertEquals(AskPlanWire.BAD_LIMIT, code(tools(call("top_merchants", limit = "\"5\""))))
        assertEquals(AskPlanWire.BAD_LIMIT, code(tools(call("top_merchants", limit = "2.5"))))
        assertEquals(AskPlanWire.BAD_LIMIT, code(tools(call("top_merchants", limit = "[5]"))))
        assertEquals(AskPlanWire.BAD_PERIOD, code(tools(call("spending_summary", period("next_week")))))
        assertEquals(AskPlanWire.BAD_PERIOD, code(tools(call("spending_summary", period("month")))))
        assertEquals(AskPlanWire.BAD_PERIOD, code(tools(call("spending_summary", period("month", month = "August")))))
        assertEquals(AskPlanWire.BAD_PERIOD, code(tools(call("spending_summary", period("range", from = "2026-10-10", to = "2026-10-01")))))
        assertEquals(AskPlanWire.BAD_PERIOD, code(tools(call("spending_summary", period("range", from = "yesterday", to = "2026-10-01")))))
        assertEquals(AskPlanWire.BAD_PERIOD, code(tools(call("spending_summary", "\"last_month\""))))
        assertEquals(AskPlanWire.BAD_MERCHANT, code(tools(call("merchant_spend", merchant = "\"" + "z".repeat(61) + "\""))))
        assertEquals(AskPlanWire.BAD_MERCHANT, code(tools(call("merchant_spend", merchant = "\"!!!\""))))
        assertEquals(AskPlanWire.BAD_MERCHANT, code(tools(call("merchant_spend", merchant = "42"))))
    }

    @Test
    fun `a merchant carrying personal data or hidden characters is rejected`() {
        assertEquals(AskPlanWire.UNSAFE_MERCHANT, code(tools(call("merchant_spend", merchant = "\"Ravi 98000 00012\""))))
        assertEquals(AskPlanWire.UNSAFE_MERCHANT, code(tools(call("merchant_spend", merchant = "\"Chai\\u202e Point\""))))
        assertEquals(AskPlanWire.UNSAFE_MERCHANT, code(tools(call("merchant_spend", merchant = "\"ravi@okhdfc\""))))
    }

    @Test
    fun `text that is not one JSON object is rejected and huge or nested replies too`() {
        assertEquals(AskPlanWire.NOT_JSON_OBJECT, code("Sure! Here is the plan."))
        assertEquals(AskPlanWire.NOT_JSON_OBJECT, code("[]"))
        assertEquals(AskPlanWire.NOT_JSON_OBJECT, code(""))
        assertEquals(AskPlanWire.TOO_LONG, code(" ".repeat(AskPlanWire.MAX_TEXT + 1)))
        assertEquals(AskPlanWire.TOO_LONG, code("[".repeat(10_000)))
        assertEquals(AskPlanWire.NOT_JSON_OBJECT, code("[".repeat(4_000)))
        // The deepest reply that still fits under the size cap never reaches the parser (AOS-635).
        assertEquals(AskPlanWire.NOT_JSON_OBJECT, code("[".repeat(AskPlanWire.MAX_TEXT)))
        assertEquals(AskPlanWire.NOT_JSON_OBJECT, code("""{"action":"tools","reason":null,"calls":[[[[]]]]}"""))
    }

    @Test
    fun `every plan encodes to the shape it reads from`() {
        val plans = listOf(
            AskPlan.Decline(DeclineReason.FUTURE),
            AskPlan.Tools(listOf(
                AskToolCall.SpendingSummary(PeriodSpec.range(LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 30))),
                AskToolCall.MerchantSpend(PeriodSpec.month(YearMonth.of(2026, 7)), MerchantQuery.of("Swiggy Instamart")!!),
                AskToolCall.SpendingTrend(PeriodSpec.of(PeriodPreset.THIS_YEAR), Bucket.MONTH, TransactionCategory.FOOD)
            )),
            AskPlan.Tools(listOf(AskToolCall.TopMerchants(PeriodSpec.of(PeriodPreset.TODAY), TransactionCategory.HEALTH, 10), AskToolCall.CategorySpend(lastMonth)))
        )
        for (p in plans) assertEquals(p, valid(AskPlanWire.encode(p).toString()))
        val encoded = AskPlanWire.encode(AskToolCall.CategorySpend(lastMonth))
        assertEquals(AskPlanWire.CALL_FIELDS, encoded.keys)
        assertEquals(JsonNull, encoded["merchant"])
        assertEquals(JsonArray(emptyList()), AskPlanWire.encode(AskPlan.Decline(DeclineReason.UNCLEAR))["calls"])
        assertEquals(JsonPrimitive("unclear"), (AskPlanWire.encode(AskPlan.Decline(DeclineReason.UNCLEAR)) as JsonObject)["reason"])
    }

    @Test
    fun `merchant queries compare by their words and refuse empty or long names`() {
        assertEquals(MerchantQuery.of("Swiggy Instamart"), MerchantQuery.of(" swiggy   INSTAMART!"))
        assertEquals("swiggy instamart", MerchantQuery.of("Swiggy-Instamart")!!.key)
        assertEquals(MerchantQuery.of("A").hashCode(), MerchantQuery.of("a").hashCode())
        assertNull(MerchantQuery.of("   "))
        assertNull(MerchantQuery.of("x".repeat(61)))
        assertEquals("MerchantQuery(Zepto)", MerchantQuery.of("Zepto").toString())
        assertFailsWith<IllegalArgumentException> { AskToolCall.TopMerchants(lastMonth, limit = 11) }
        assertFailsWith<IllegalArgumentException> { AskPlan.Tools(emptyList()) }
        assertEquals(AskTool.MERCHANT_SPEND, AskTool.fromKey("merchant_spend"))
        assertEquals(DeclineReason.FUTURE, DeclineReason.fromKey("future"))
        assertNull(DeclineReason.fromKey("nope"))
    }
}
