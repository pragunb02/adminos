package dev.adminos.api.domain.financial

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.*

/** The financial wire DTOs round-trip through JSON in camelCase, and a missing required field is rejected (AOS-312, D-31). */
class FinancialDtosCoverageTest {

    private val json = Json
    private val withDefaults = Json { encodeDefaults = true }
    private val userId = UUID.randomUUID()
    private val at = Instant.parse("2026-10-10T06:30:00Z")

    private fun <T> roundTrip(serializer: KSerializer<T>, value: T, format: Json = json) =
        assertEquals(value, format.decodeFromString(serializer, format.encodeToString(serializer, value)))

    @Test
    fun `a category override defaults to remembering the payee`() {
        val request = json.decodeFromString(CategoryOverrideRequest.serializer(), """{"category":"food"}""")
        assertEquals(CategoryOverrideRequest("food", null, remember = true), request)
        assertEquals("""{"category":"food"}""", json.encodeToString(CategoryOverrideRequest.serializer(), request))
    }

    @Test
    fun `a category override with a subcategory and remember false keeps both`() {
        val request = CategoryOverrideRequest("food", "tea", remember = false)
        assertEquals("""{"category":"food","subcategory":"tea","remember":false}""", json.encodeToString(CategoryOverrideRequest.serializer(), request))
        roundTrip(CategoryOverrideRequest.serializer(), request)
        roundTrip(CategoryOverrideRequest.serializer(), CategoryOverrideRequest("food"), withDefaults)
    }

    @Test
    fun `a category override without a category is rejected`() {
        assertFailsWith<SerializationException> { json.decodeFromString(CategoryOverrideRequest.serializer(), """{"subcategory":"tea"}""") }
    }

    @Test
    fun `transaction responses round-trip`() {
        val t = Transaction(userId = userId, type = TransactionType.DEBIT, amount = BigDecimal("450.00"), merchantName = "Swiggy",
            accountLast4 = "4821", paymentMethod = PaymentMethod.UPI, transactedAt = at)
        roundTrip(TransactionResponse.serializer(), TransactionResponse.from(t))
        assertFailsWith<SerializationException> { json.decodeFromString(TransactionResponse.serializer(), """{"id":"x"}""") }
    }

    @Test
    fun `transaction summaries round-trip`() {
        val summary = TransactionSummaryResponse(PeriodInfo("2026-10-01T00:00:00Z", "2026-10-31T23:59:59Z"), 1149.0, mapOf("food" to 450.0, "shopping" to 699.0))
        roundTrip(TransactionSummaryResponse.serializer(), summary)
        roundTrip(PeriodInfo.serializer(), summary.period)
        assertFailsWith<SerializationException> { json.decodeFromString(TransactionSummaryResponse.serializer(), """{"totalSpending":1}""") }
        assertFailsWith<SerializationException> { json.decodeFromString(PeriodInfo.serializer(), """{"from":"x"}""") }
    }

    @Test
    fun `account responses round-trip`() {
        val a = Account(userId = userId, bankName = "HDFC Bank", bankCode = "HDFC", accountLast4 = "4821", accountName = "HDFC Bank ****4821", createdAt = at)
        roundTrip(AccountResponse.serializer(), AccountResponse.from(a))
        assertFailsWith<SerializationException> { json.decodeFromString(AccountResponse.serializer(), """{"id":"x"}""") }
    }

    @Test
    fun `subscription responses and summaries round-trip`() {
        val s = Subscription(userId = userId, name = "Netflix", amount = BigDecimal("649"), billingCycle = BillingCycle.MONTHLY,
            nextBillingDate = LocalDate.of(2026, 11, 5), priceChangePct = BigDecimal("30.06"), transactionIds = listOf(UUID.randomUUID()), createdAt = at)
        roundTrip(SubscriptionResponse.serializer(), SubscriptionResponse.from(s))
        val summary = SubscriptionSummaryResponse(948.67, activeCount = 2, flaggedCount = 1, cancelledCount = 0,
            byCategory = mapOf("entertainment" to CategoryBreakdown(2, 948.67)))
        roundTrip(SubscriptionSummaryResponse.serializer(), summary)
        roundTrip(SubscriptionSummaryResponse.serializer(), summary.copy(currency = "USD"))
        roundTrip(SubscriptionSummaryResponse.serializer(), summary, withDefaults)
        assertFailsWith<SerializationException> { json.decodeFromString(SubscriptionResponse.serializer(), """{"id":"x"}""") }
        assertFailsWith<SerializationException> { json.decodeFromString(SubscriptionSummaryResponse.serializer(), """{"currency":"INR"}""") }
        assertFailsWith<SerializationException> { json.decodeFromString(CategoryBreakdown.serializer(), """{"count":1}""") }
    }

    @Test
    fun `bill responses round-trip`() {
        val b = Bill(userId = userId, billerName = "HDFC Credit Card", amount = BigDecimal("15230.45"), minimumDue = BigDecimal("761.52"),
            dueDate = LocalDate.of(2026, 10, 20), paidAt = at, paidAmount = BigDecimal("15230.45"), paymentTxnId = UUID.randomUUID(), createdAt = at)
        roundTrip(BillResponse.serializer(), BillResponse.from(b))
        assertFailsWith<SerializationException> { json.decodeFromString(BillResponse.serializer(), """{"id":"x"}""") }
    }

    @Test
    fun `dashboard responses round-trip`() {
        val item = DashboardActionItem("1", "bill", "Tata Power bill", "Due tomorrow — ₹2350.75", "warning", "1", "View")
        val briefing = DashboardBriefingSummary("b", "2026-10-09", "2026-10-15", "You spent ₹12,345.50", 12345.5)
        val home = DashboardHomeResponse("Good morning", DashboardSummaryResponse(300.0, 3, 2, 4, 1), listOf(item), briefing)
        roundTrip(DashboardHomeResponse.serializer(), home)
        roundTrip(DashboardHomeResponse.serializer(), home.copy(latestBriefing = null))
        assertFailsWith<SerializationException> { json.decodeFromString(DashboardHomeResponse.serializer(), """{"greeting":"hi"}""") }
        assertFailsWith<SerializationException> { json.decodeFromString(DashboardSummaryResponse.serializer(), """{"openAnomalies":1}""") }
        assertFailsWith<SerializationException> { json.decodeFromString(DashboardActionItem.serializer(), """{"id":"1"}""") }
        assertFailsWith<SerializationException> { json.decodeFromString(DashboardBriefingSummary.serializer(), """{"id":"1"}""") }
    }
}
