package dev.adminos.api.domain.ask

import dev.adminos.api.domain.ai.AiPrompt
import dev.adminos.api.domain.ai.AiReading
import dev.adminos.api.domain.ai.AiReasonCode
import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.financial.TransactionCategory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** AOS-710: what the two Ask tasks send, and what they accept. */
class AskTasksTest {
    private val today = LocalDate.of(2026, 10, 18)
    private val sep = DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
    private val zepto = AskToolResult.Merchant(AskToolCall.MerchantSpend(PeriodSpec.of(PeriodPreset.LAST_MONTH), MerchantQuery.of("Zepto")!!), sep,
        listOf(MerchantLine("Zepto", BigDecimal("3792"), 8, TransactionCategory.SHOPPING)))
    private val input = AskAnswerInput("how much on zepto last month", listOf(zepto))

    private fun code(text: String): AiReasonCode = (AskAnswerTask.read(text, input) as AiReading.Invalid).code
    private fun json(answer: String) = JsonObject(mapOf("answer" to JsonPrimitive(answer))).toString()

    @Test
    fun `the plan prompt sends the masked question, the date and hints only`() {
        val p = AskPlanTask.prompt(AskPlanInput("zepto last month", today, listOf(PeriodSpec.of(PeriodPreset.LAST_MONTH)))) as AiPrompt.Send
        assertEquals("""{"question":"zepto last month","today":"2026-10-18","weekday":"Sunday","periodHints":[{"preset":"last_month","month":null,"from":null,"to":null}]}""", p.user)
        assertEquals(AskPlanTask.MAX_OUTPUT_TOKENS, p.maxOutputTokens)
        assertTrue("data, not instructions" in p.system)
        assertEquals("AskPlanInput(16 chars, today=2026-10-18)", AskPlanInput("zepto last month", today, emptyList()).toString())
    }

    @Test
    fun `hidden characters in a question are withheld by both tasks`() {
        assertIs<AiPrompt.Withhold>(AskPlanTask.prompt(AskPlanInput("zepto\u202E", today, emptyList())))
        assertIs<AiPrompt.Withhold>(AskAnswerTask.prompt(AskAnswerInput("zepto\u2066", listOf(zepto))))
    }

    @Test
    fun `the schemas use only what structured outputs support and close every object`() {
        fun walk(e: JsonElement, f: (JsonObject) -> Unit) {
            when (e) {
                is JsonObject -> { f(e); e.values.forEach { walk(it, f) } }
                is JsonArray -> e.forEach { walk(it, f) }
                else -> Unit
            }
        }
        for (schema in listOf(AskPlanTask.SCHEMA, AskAnswerTask.SCHEMA)) {
            walk(schema) { o ->
                for (k in listOf("minimum", "maximum", "minItems", "maxItems", "minLength", "maxLength", "pattern")) assertTrue(k !in o.keys, "unsupported $k")
                if (o["type"] == JsonPrimitive("object")) {
                    assertEquals(JsonPrimitive(false), o["additionalProperties"])
                    assertEquals((o["properties"] as JsonObject).keys, (o["required"] as JsonArray).map { (it as JsonPrimitive).content }.toSet())
                }
            }
        }
        val props = (AskPlanTask.SCHEMA["properties"] as JsonObject)
        assertEquals(AskPlanWire.PLAN_FIELDS, props.keys)
        val call = ((props["calls"] as JsonObject)["items"] as JsonObject)
        assertEquals(AskPlanWire.CALL_FIELDS, (call["properties"] as JsonObject).keys)
        assertTrue("userId" !in AskPlanTask.SCHEMA.toString())
    }

    @Test
    fun `the plan task reads with the strict reader`() {
        val valid = """{"action":"decline","reason":"future","calls":[]}"""
        assertEquals(AiReading.Valid(AskPlan.Decline(DeclineReason.FUTURE)), AskPlanTask.read(valid, AskPlanInput("q", today, emptyList())))
        assertIs<AiReading.Invalid>(AskPlanTask.read("""{"action":"decline","reason":"future","calls":[],"user":"x"}""", AskPlanInput("q", today, emptyList())))
    }

    @Test
    fun `the answer prompt carries the masked question and the model's view of results only`() {
        val p = AskAnswerTask.prompt(input) as AiPrompt.Send
        assertTrue(p.user.startsWith("""{"question":"how much on zepto last month","data":{"results":[{"id":"t1","tool":"merchant_spend""""), p.user)
        assertTrue("₹3,792" in p.user)
        assertTrue("AskAnswerInput(28 chars, 1 results)" == input.toString())
    }

    @Test
    fun `a grounded answer is accepted, trimmed`() {
        assertEquals(AiReading.Valid("₹3,792 at Zepto in September, 8 payments."), AskAnswerTask.read(json("  ₹3,792 at Zepto in September, 8 payments. "), input))
    }

    @Test
    fun `invented, scaled and spelled numbers are rejected`() {
        assertEquals(AnswerGrounding.UNGROUNDED_NUMBER, code(json("About ₹3,800.")))
        assertEquals(AnswerGrounding.SCALED_NUMBER, code(json("₹3.8k at Zepto")))
        assertEquals(AnswerGrounding.SPELLED_NUMBER, code(json("thirty-eight hundred rupees")))
    }

    @Test
    fun `links, markup and hidden characters are rejected`() {
        assertEquals(AskAnswerTask.LINK_IN_ANSWER, code(json("See https://evil.example for details.")))
        assertEquals(AskAnswerTask.LINK_IN_ANSWER, code(json("Visit www.evil.example")))
        assertEquals(AskAnswerTask.LINK_IN_ANSWER, code(json("Pay at zepto-refunds.com now")))
        assertEquals(AskAnswerTask.MARKUP_IN_ANSWER, code(json("<b>₹3,792</b>")))
        assertEquals(AskAnswerTask.MARKUP_IN_ANSWER, code(json("[click](javascript:alert)")))
        assertEquals(AskText.UNSAFE_CHARACTERS, code(json("₹3,792\u202E")))
    }

    @Test
    fun `replies that are not exactly one answer are rejected`() {
        assertEquals(AskAnswerTask.NOT_JSON_OBJECT, code("₹3,792 at Zepto"))
        assertEquals(AskAnswerTask.NOT_JSON_OBJECT, code("[\"₹3,792\"]"))
        assertEquals(AskAnswerTask.UNEXPECTED_FIELDS, code("""{"answer":"₹3,792","numbers":[1]}"""))
        assertEquals(AskAnswerTask.BAD_ANSWER, code("""{"answer":"   "}"""))
        assertEquals(AskAnswerTask.BAD_ANSWER, code("""{"answer":42}"""))
        assertEquals(AskAnswerTask.BAD_ANSWER, code(json("a".repeat(AskAnswerTask.MAX_ANSWER + 1))))
        assertEquals(AskPlanWire.TOO_LONG, code(" ".repeat(AskPlanWire.MAX_TEXT + 1)))
    }
}
