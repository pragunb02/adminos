package dev.adminos.api.domain.financial.mandates

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.pipeline.*
import dev.adminos.api.domain.ingestion.sms.SmsIntake
import dev.adminos.api.domain.ingestion.sms.SmsIntakeResult
import dev.adminos.api.domain.ingestion.sms.SmsMessage
import dev.adminos.api.domain.ingestion.sms.SmsParserRegistry
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SMS → mandate → debit → recurring transaction, and GET /api/v1/mandates (AOS-502). */
class MandateFlowTest {
    private val user = User(id = UUID.randomUUID(), email = "pragun@example.in", googleId = "g-502")
    private val events = InMemoryIngestEventRepository()
    private val transactions = InMemoryTransactionRepository()
    private val mandates = InMemoryMandateRepository()
    private val service = MandateService(mandates, transactions)
    private val intake = SmsIntake(SmsParserRegistry(), IngestEventService(events), service, dev.adminos.api.domain.financial.paylater.testPayLaterBills())
    private val processor = IngestProcessor(
        events,
        IngestPipeline(listOf(ToTransactionStep(), NormalizeMerchantStep(MerchantNormalizer()), CategorizeStep(CategorizationService()))),
        RepositoryOutcomeWriter(transactions, events, dev.adminos.api.domain.financial.links.InMemoryTransactionLinkRepository()),
        listeners = listOf(MandateDebitListener(service))
    )

    private suspend fun sms(text: String, at: String) = intake.accept(user.id, SmsMessage("AX-HDFCBK-S", text, Instant.parse(at)))

    @Test
    fun `set-up SMS, then the debit SMS, the debit pays the mandate and is marked recurring`() = runBlocking<Unit> {
        assertEquals(SmsIntakeResult.Mandate, sms("Your UPI AutoPay mandate for NETFLIX of Rs 649.00 Monthly has been successfully created. UMN: 7a8b9c0d1e@hdfcbank", "2026-10-01T05:00:00Z"))
        assertEquals(SmsIntakeResult.Accepted, sms("Rs.649.00 debited from A/c XX1234 on 10-10-26 towards UPI Mandate NETFLIX. UMN 7a8b9c0d1e@hdfcbank.", "2026-10-10T04:00:00Z"))
        while (processor.processBatch() > 0) {}

        val t = transactions.findByUserId(user.id).single()
        assertEquals(mapOf("mandate" to "true", "umn" to "7a8b9c0d1e@hdfcbank"), t.metadata, "only the mandate markers travel")
        assertTrue(t.isRecurring)
        val m = mandates.findByUserId(user.id).single()
        assertEquals(t.id, m.lastDebitTxnId)
        assertEquals(LocalDate.of(2026, 11, 10), m.nextDebitOn)
        assertEquals(m.id, t.recurringGroupId)
    }

    @Test
    fun `an ordinary payment carries no metadata into the transaction`() = runBlocking<Unit> {
        sms("Sent Rs.249.00 From HDFC Bank A/C *1234 To SWIGGY On 16/10/26 Ref 123456789012", "2026-10-16T04:00:00Z")
        while (processor.processBatch() > 0) {}
        val t = transactions.findByUserId(user.id).single()
        assertEquals(null, t.metadata)
        assertFalse(t.isRecurring)
    }

    // ── GET /api/v1/mandates ──

    private val jwtService = JwtService(AppConfig.load().auth)

    @Test
    fun `the list shows each mandate's status, amounts as strings, and whether it has a reference, never the UMN`() = testApplication {
        runBlocking { sms("e-NACH mandate registered in favour of BAJAJ FINANCE LTD for Rs 5,400.00 Monthly, valid till 05 Mar 2030. UMRN HDFC7000000012345", "2026-10-01T05:00:00Z") }
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            routing { route("/api/v1") { mandateRoutes(service) } }
        }
        val res = client.get("/api/v1/mandates") { cookie(ACCESS_TOKEN_COOKIE, jwtService.generateAccessToken(user)) }
        assertEquals(HttpStatusCode.OK, res.status)
        val m = Json.parseToJsonElement(res.bodyAsText()).jsonObject["data"]!!.jsonArray.single().jsonObject
        assertEquals("BAJAJ FINANCE LTD", m["payee"]!!.jsonPrimitive.content)
        assertEquals("nach", m["rail"]!!.jsonPrimitive.content)
        assertEquals("5400.00", m["amount"]!!.jsonPrimitive.content)
        assertEquals("monthly", m["frequency"]!!.jsonPrimitive.content)
        assertEquals("active", m["status"]!!.jsonPrimitive.content)
        assertEquals("2030-03-05", m["validUntil"]!!.jsonPrimitive.content)
        assertEquals(true, m["hasReference"]!!.jsonPrimitive.boolean)
        assertTrue("HDFC7000000012345" !in res.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/mandates").status)
    }
}
