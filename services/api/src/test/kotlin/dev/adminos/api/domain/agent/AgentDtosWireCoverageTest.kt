package dev.adminos.api.domain.agent

import kotlinx.serialization.KSerializer
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JSON wire contract of the agent DTOs (AOS-312): each round-trips through the API's Json settings,
 * required fields are enforced on decode, and optional fields default when absent.
 */
class AgentDtosWireCoverageTest {
    /** Same settings as configureSerialization(). */
    private val wire = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val compact = Json { ignoreUnknownKeys = true }

    private val anomaly = AnomalyResponse(
        id = "a1", transactionId = "t1", type = "card_testing", confidenceScore = 0.9,
        reason = "Amount of exactly ₹1.00 matches card testing pattern", agentExplanation = null,
        status = "open", resolvedAt = null, resolvedBy = null, createdAt = "2026-10-10T06:30:00Z"
    )
    private val insight = InsightResponse(
        id = "i1", type = "bill_due", title = "HDFC card bill due", body = "₹18,240 due on 20 Oct",
        severity = "warning", actionType = "pay_bill", entityId = null, status = "pending"
    )
    private val briefing = BriefingResponse(
        id = "b1", periodStart = "2026-10-05", periodEnd = "2026-10-11", type = "weekly", content = "Weekly summary",
        totalSpent = "12450.50", totalIncome = null, topCategories = listOf(CategoryAmountResponse("food", "4200.25")),
        subscriptionsFlagged = 1, anomaliesDetected = 0, billsUpcoming = 2, status = "generated",
        insights = listOf(insight), createdAt = "2026-10-12T03:30:00Z"
    )

    private fun <T> assertRoundTrips(serializer: KSerializer<T>, value: T) {
        assertEquals(value, wire.decodeFromString(serializer, wire.encodeToString(serializer, value)))
    }

    private fun <T> assertRequiresEveryField(serializer: KSerializer<T>, value: T, optional: Set<String> = emptySet()) {
        val full = wire.encodeToJsonElement(serializer, value).jsonObject
        for (field in full.keys - optional) {
            val missing = JsonObject(full - field)
            assertFailsWith<MissingFieldException>(field) { wire.decodeFromJsonElement(serializer, missing) }
        }
    }

    @Test
    fun `anomaly response round-trips and requires every field`() {
        assertRoundTrips(AnomalyResponse.serializer(), anomaly)
        assertRequiresEveryField(AnomalyResponse.serializer(), anomaly)
    }

    @Test
    fun `insight response round-trips and requires every field`() {
        assertRoundTrips(InsightResponse.serializer(), insight)
        assertRequiresEveryField(InsightResponse.serializer(), insight)
    }

    @Test
    fun `category amount response round-trips and requires every field`() {
        val c = CategoryAmountResponse("food", "4200.25")
        assertRoundTrips(CategoryAmountResponse.serializer(), c)
        assertRequiresEveryField(CategoryAmountResponse.serializer(), c)
    }

    @Test
    fun `cancellation guidance response round-trips and requires every field`() {
        val c = CancellationGuidanceResponse("s1", "upi_autopay", listOf("Open the UPI app", "Revoke it"))
        assertRoundTrips(CancellationGuidanceResponse.serializer(), c)
        assertRequiresEveryField(CancellationGuidanceResponse.serializer(), c)
    }

    @Test
    fun `briefing response round-trips and requires every field except insights`() {
        assertRoundTrips(BriefingResponse.serializer(), briefing)
        assertRequiresEveryField(BriefingResponse.serializer(), briefing, optional = setOf("insights"))
    }

    @Test
    fun `briefing response without insights decodes them as null`() {
        val json = wire.encodeToJsonElement(BriefingResponse.serializer(), briefing).jsonObject
        val decoded = wire.decodeFromJsonElement(BriefingResponse.serializer(), JsonObject(json - "insights"))
        assertNull(decoded.insights)
    }

    @Test
    fun `briefing response writes null insights on the wire but omits them in compact json`() {
        val noInsights = briefing.copy(insights = null)
        assertTrue("insights" in wire.encodeToJsonElement(BriefingResponse.serializer(), noInsights).jsonObject)
        assertFalse("insights" in compact.encodeToJsonElement(BriefingResponse.serializer(), noInsights).jsonObject)
        assertTrue("insights" in compact.encodeToJsonElement(BriefingResponse.serializer(), briefing).jsonObject)
    }

    @Test
    fun `anomaly resolve request requires a status`() {
        assertEquals("dismissed", wire.decodeFromString(AnomalyResolveRequest.serializer(), """{"status":"dismissed"}""").status)
        assertFailsWith<MissingFieldException> { wire.decodeFromString(AnomalyResolveRequest.serializer(), "{}") }
    }

    @Test
    fun `subscription dismiss request reason is optional`() {
        val s = SubscriptionDismissRequest.serializer()
        assertNull(wire.decodeFromString(s, "{}").reason)
        assertEquals("still watching", wire.decodeFromString(s, """{"reason":"still watching"}""").reason)
        assertEquals("""{"reason":null}""", wire.encodeToString(s, SubscriptionDismissRequest()))
        assertEquals("{}", compact.encodeToString(s, SubscriptionDismissRequest()))
        assertEquals("""{"reason":"x"}""", compact.encodeToString(s, SubscriptionDismissRequest("x")))
    }
}
