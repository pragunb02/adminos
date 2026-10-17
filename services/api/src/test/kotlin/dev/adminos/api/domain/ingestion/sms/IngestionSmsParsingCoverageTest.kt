package dev.adminos.api.domain.ingestion.sms

import dev.adminos.api.InfraIngestTestKit.assertRejectsMissingFields
import dev.adminos.api.InfraIngestTestKit.assertRoundTrips
import dev.adminos.api.InfraIngestTestKit.jwtService
import dev.adminos.api.InfraIngestTestKit.user
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.identity.DeviceTokenService
import dev.adminos.api.domain.identity.InMemoryDeviceTokenRepository
import dev.adminos.api.domain.identity.Scopes
import dev.adminos.api.domain.identity.configureDeviceAuthentication
import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bank SMS parsing rules, the parser registry, SMS intake and the raw SMS route (AOS-312). */
class IngestionSmsParsingCoverageTest {

    private val generic = SmsParserRegistry.GENERIC

    // ── BankSmsParser ──

    @Test
    fun `a message without a money verb is not a transaction`() {
        assertNull(generic.parse("Your HDFC Bank e-statement for Oct 2026 is ready"))
    }

    @Test
    fun `a debit without any amount is not a transaction`() {
        assertNull(generic.parse("Your A/c XX1234 has been debited. Call 1800-123 if not you."))
    }

    @Test
    fun `a balance figure is never taken as the amount`() {
        assertNull(generic.parse("Your A/c XX1234 debited. Avl Bal Rs 5,000.00"))
    }

    @Test
    fun `a zero amount is not a transaction`() {
        assertNull(generic.parse("Rs 0 debited from A/c XX1234"))
    }

    @Test
    fun `SBI style amount without currency, account, payee and reference are read`() {
        val p = generic.parse("Dear Customer, Your a/c no. XXXXXXXX5678 is debited by 249.0 on 01Oct26 trf to SWIGGY Ref No 123456789012. -SBI")!!
        assertEquals(IngestDirection.DEBIT, p.direction)
        assertEquals(BigDecimal("249.00"), p.amount)
        assertEquals("5678", p.accountHint)
        assertEquals("SWIGGY", p.merchantRaw)
        assertEquals("123456789012", p.reference)
        assertNull(p.paymentMethod)
        assertNull(p.upiVpa)
    }

    @Test
    fun `a credit verb before a debit verb makes it a credit`() {
        assertEquals(IngestDirection.CREDIT, generic.parse("Refund of Rs 249 credited to A/c XX1234 for order paid at AMAZON")!!.direction)
    }

    @Test
    fun `ICICI three-digit masks are not kept as an account hint`() {
        val p = generic.parse("ICICI Bank Acct XX123 debited for Rs 100.00 on 01-Oct-26; RAMU credited. UPI:123456789012.")!!
        assertNull(p.accountHint)
        assertEquals("RAMU", p.merchantRaw)
        assertEquals(PaymentMethod.UPI, p.paymentMethod)
        assertEquals("123456789012", p.reference)
    }

    @Test
    fun `credit card spends are card_credit with the merchant after At`() {
        val p = generic.parse("Rs 1,499.00 spent on your HDFC Bank Credit Card XX4321 at AMAZON on 2026-10-01")!!
        assertEquals(BigDecimal("1499.00"), p.amount)
        assertEquals(PaymentMethod.CARD_CREDIT, p.paymentMethod)
        assertEquals("4321", p.accountHint)
        assertEquals("AMAZON", p.merchantRaw)
    }

    @Test
    fun `debit card spends are card_debit`() {
        assertEquals(PaymentMethod.CARD_DEBIT, generic.parse("Rs 500 spent on Debit Card XX1111 at DMART on 01Oct")!!.paymentMethod)
    }

    @Test
    fun `ATM withdrawals are cash`() {
        assertEquals(PaymentMethod.CASH, generic.parse("Rs 2000 withdrawn at ATM from A/c XX2222")!!.paymentMethod)
    }

    @Test
    fun `NEFT transfers are netbanking`() {
        assertEquals(PaymentMethod.NETBANKING, generic.parse("Rs 10,000.00 debited from A/c XX3333 via NEFT to RAHUL")!!.paymentMethod)
    }

    @Test
    fun `a UPI handle makes it UPI and is kept in lowercase`() {
        val p = generic.parse("Rs 249 sent to Ramu@YBL from A/c XX1234")!!
        assertEquals("ramu@ybl", p.upiVpa)
        assertEquals(PaymentMethod.UPI, p.paymentMethod)
    }

    @Test
    fun `a bank pattern that captures a blank or one-letter payee falls through to the common patterns`() {
        val parser = BankSmsParser("test", setOf("TESTBK"), listOf(Regex("PAYEE:(.*?);"), Regex("NAME:(.*?);")))
        val p = parser.parse("PAYEE: ; NAME:X; Rs 10 sent To CHAI POINT On 01-10")!!
        assertEquals("CHAI POINT", p.merchantRaw)
        assertEquals("test", p.bankCode)
    }

    // ── SenderId ──

    @Test
    fun `senders with a few digits are still business headers`() {
        assertEquals("AB1234", SenderId.parse("AX-AB1234-S")!!.header)
    }

    @Test
    fun `phone numbers are not business senders`() {
        assertNull(SenderId.parse("+919876543210"))
    }

    @Test
    fun `the P suffix marks a promotional sender`() {
        assertTrue(SenderId.parse("AX-HDFCBK-P")!!.isPromotional)
        assertFalse(SenderId.parse("AX-HDFCBK")!!.isPromotional)
    }

    @Test
    fun `an SMS message never prints its text`() {
        val s = SmsMessage("AX-HDFCBK-S", "Sent Rs.249.00 OTP 123456", Instant.EPOCH).toString()
        assertFalse("249" in s || "OTP" in s, s)
        assertTrue("redacted" in s)
    }

    // ── Registry ──

    private val hdfcOnly = object : SmsParser {
        override val bankCode = "hdfc"
        override val headers = setOf("HDFCBK")
        override fun parse(text: String): ParsedSmsTransaction? = null
    }

    @Test
    fun `without a generic parser in the list the built-in one is the fallback`() {
        val registry = SmsParserRegistry(listOf(hdfcOnly))
        val r = registry.parse(SmsMessage("AX-NEWBNK-S", "Rs 100 debited from A/c XX1234", Instant.EPOCH))
        assertIs<SmsParseResult.Transaction>(r)
        assertNull(r.parsed.bankCode)
    }

    @Test
    fun `when the bank parser can't read a message the generic parser tries`() {
        val r = SmsParserRegistry(listOf(hdfcOnly)).parse(SmsMessage("AX-HDFCBK-S", "Rs 100 debited from A/c XX1234", Instant.EPOCH))
        assertIs<SmsParseResult.Transaction>(r)
    }

    @Test
    fun `promotional senders are ignored`() {
        val r = SmsParserRegistry().parse(SmsMessage("AX-HDFCBK-P", "Rs 100 debited from A/c XX1234", Instant.EPOCH))
        assertEquals(SmsParseResult.Ignored("promotional sender"), r)
    }

    @Test
    fun `a readable sender with an unreadable text is ignored`() {
        val r = SmsParserRegistry().parse(SmsMessage("AX-HDFCBK-S", "Thank you for banking with us", Instant.EPOCH))
        assertEquals(SmsParseResult.Ignored("not a recognised transaction message"), r)
    }

    // ── Intake ──

    private val events = InMemoryIngestEventRepository()
    private val intake = SmsIntake(SmsParserRegistry(), IngestEventService(events), dev.adminos.api.domain.financial.mandates.MandateService(dev.adminos.api.domain.financial.mandates.InMemoryMandateRepository(), dev.adminos.api.domain.financial.InMemoryTransactionRepository()), dev.adminos.api.domain.financial.paylater.testPayLaterBills())
    private val owner = user()

    @Test
    fun `a message from an unknown bank is stored without bank metadata`() = runBlocking<Unit> {
        assertEquals(SmsIntakeResult.Accepted, intake.accept(owner.id, SmsMessage("AX-NEWBNK-S", "Rs 100 debited from A/c XX1234", Instant.parse("2026-10-01T03:42:00Z"))))
        assertNull(events.findByUser(owner.id).single().metadata)
    }

    @Test
    fun `a known bank's message records the bank`() = runBlocking<Unit> {
        intake.accept(owner.id, SmsMessage("AX-HDFCBK-S", "Rs 100 debited from A/c XX1234", Instant.parse("2026-10-01T03:42:00Z")))
        assertEquals(mapOf("bank" to "hdfc"), events.findByUser(owner.id).single().metadata)
    }

    // ── Raw SMS route ──

    private val deviceTokens = DeviceTokenService(InMemoryDeviceTokenRepository(), jwtService)

    private fun rawTest(block: suspend ApplicationTestBuilder.(String) -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            configureDeviceAuthentication(deviceTokens)
            routing { route("/api/v1") { rawSmsRoutes(intake) } }
        }
        block(runBlocking { deviceTokens.issue(owner.id, "Ravi's Mac", setOf(Scopes.INGEST_SMS)).token })
    }

    private suspend fun ApplicationTestBuilder.post(token: String, body: String) = client.post("/api/v1/ingest/sms/raw") {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
    }

    private fun msg(sender: String = "AX-HDFCBK-S", text: String = "Rs 100 debited from A/c XX1234", at: String = "2026-10-01T09:12:00+05:30") =
        buildJsonObject { put("sender", sender); put("text", text); put("receivedAt", at) }

    private fun body(vararg m: JsonObject) = buildJsonObject { put("messages", JsonArray(m.toList())) }.toString()

    private fun statuses(res: String) = Json.parseToJsonElement(res).jsonObject["data"]!!.jsonObject["results"]!!.jsonArray
        .map { it.jsonObject["status"]!!.jsonPrimitive.content to it.jsonObject["reason"]?.jsonPrimitive?.contentOrNull }

    @Test
    fun `an empty message list is a 400`() = rawTest { token ->
        assertEquals(HttpStatusCode.BadRequest, post(token, body()).status)
    }

    @Test
    fun `more than 100 messages is a 400`() = rawTest { token ->
        assertEquals(HttpStatusCode.BadRequest, post(token, body(*Array(101) { msg() })).status)
    }

    @Test
    fun `a body that is not the expected shape is a 400 that does not echo it`() = rawTest { token ->
        val res = post(token, """{"messages":"Sent Rs.249 secret"}""")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertFalse("secret" in res.bodyAsText())
    }

    @Test
    fun `over-long text or sender is ignored as too long`() = rawTest { token ->
        val res = post(token, body(msg(text = "x".repeat(2001)), msg(sender = "S".repeat(33))))
        assertEquals(listOf("ignored" to "too long", "ignored" to "too long"), statuses(res.bodyAsText()))
    }

    @Test
    fun `receivedAt may be an instant, including a leap second`() = rawTest { token ->
        val res = post(token, body(msg(at = "2016-12-31T23:59:60Z")))
        assertEquals(listOf("accepted" to null), statuses(res.bodyAsText()))
    }

    @Test
    fun `an unreadable receivedAt is ignored`() = rawTest { token ->
        assertEquals(listOf("ignored" to "receivedAt is not ISO-8601"), statuses(post(token, body(msg(at = "yesterday"))).bodyAsText()))
    }

    @Test
    fun `a resent message is a duplicate and an OTP is ignored with its reason`() = rawTest { token ->
        val res = post(token, body(msg(), msg(), msg(text = "123456 is your OTP for login")))
        assertEquals(listOf("accepted" to null, "duplicate" to null, "ignored" to "one-time password"), statuses(res.bodyAsText()))
        val data = Json.parseToJsonElement(res.bodyAsText()).jsonObject["data"]!!.jsonObject
        assertEquals(1, data["accepted"]!!.jsonPrimitive.int); assertEquals(1, data["duplicates"]!!.jsonPrimitive.int); assertEquals(1, data["ignored"]!!.jsonPrimitive.int)
    }

    @Test
    fun `raw SMS DTOs round-trip with and without defaults`() {
        assertRoundTrips(RawSmsBatchResponse.serializer(),
            """{"accepted":1,"duplicates":0,"ignored":1,"results":[{"index":0,"status":"accepted"},{"index":1,"status":"ignored","reason":"too long"}]}""")
        assertRoundTrips(RawSmsBatchRequest.serializer(), """{"messages":[{"sender":"AX-HDFCBK-S","text":"t","receivedAt":"2026-10-01T09:12:00+05:30"}]}""")
        assertRejectsMissingFields(RawSmsBatchRequest.serializer())
        assertRejectsMissingFields(RawSmsDto.serializer())
        assertRejectsMissingFields(RawSmsResult.serializer())
        assertRejectsMissingFields(RawSmsBatchResponse.serializer())
    }
}
