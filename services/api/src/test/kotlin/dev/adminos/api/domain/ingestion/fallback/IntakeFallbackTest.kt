package dev.adminos.api.domain.ingestion.fallback

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.mandates.InMemoryMandateRepository
import dev.adminos.api.domain.financial.mandates.MandateService
import dev.adminos.api.domain.identity.DeviceTokenService
import dev.adminos.api.domain.identity.InMemoryDeviceTokenRepository
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.Scopes
import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.domain.identity.configureDeviceAuthentication
import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.sms.SmsIntake
import dev.adminos.api.domain.ingestion.sms.SmsIntakeResult
import dev.adminos.api.domain.ingestion.sms.SmsMessage
import dev.adminos.api.domain.ingestion.sms.SmsParseResult
import dev.adminos.api.domain.ingestion.sms.SmsParserRegistry
import dev.adminos.api.domain.ingestion.sms.rawSmsRoutes
import dev.adminos.api.domain.ingestion.whatsapp.WhatsAppImportService
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-705: only bank messages no parser read reach the fallback, from SMS and WhatsApp alike. */
class IntakeFallbackTest {
    private val user = UUID(0, 9)
    private val offered = mutableListOf<UnrecognisedMessage>()
    private var answer: FallbackOffer = FallbackOffer.Queued
    private val sink = UnrecognisedMessageSink { offered += it; answer }
    private val events = InMemoryIngestEventRepository()
    private val mandates = MandateService(InMemoryMandateRepository(), InMemoryTransactionRepository())
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val intake = SmsIntake(SmsParserRegistry(), IngestEventService(events), mandates, sink) { kolkata }
    private val at = Instant.parse("2026-10-16T05:00:00Z")
    private val unknown = "Card XX4321 charged Rs.249.00 at ZEPTO on 16-10-26."

    @Test
    fun `an unrecognised bank sms is offered with its fingerprint, bank and zone`() = runBlocking<Unit> {
        assertEquals(SmsIntakeResult.Reading, intake.accept(user, SmsMessage("VM-HDFCBK-S", unknown, at)))
        val m = offered.single()
        assertEquals(MessageChannel.SMS, m.channel)
        assertEquals("hdfc", m.bankCode)
        assertEquals(kolkata, m.zone)
        assertEquals(at, m.receivedAt)
        assertEquals(64, m.sourceRef.length)
        assertNull(SmsParserRegistry().bankFor("FEDBNK"))
        intake.accept(user, SmsMessage("AD-FEDBNK-S", unknown, at))
        assertNull(offered.last().bankCode, "an unknown bank is read with no bank code")
        assertTrue(events.findByUser(user).isEmpty(), "nothing reaches the intake until confirmed")
    }

    @Test
    fun `when the fallback doesn't queue it the sms stays ignored as before`() = runBlocking<Unit> {
        for (a in listOf(FallbackOffer.Off, FallbackOffer.NoMoneyWord, FallbackOffer.AlreadyRead, FallbackOffer.Busy, FallbackOffer.Withheld(MessageRedactor.TOO_LONG))) {
            answer = a
            assertEquals(SmsIntakeResult.Ignored(SmsParseResult.UNRECOGNISED), intake.accept(user, SmsMessage("AD-FEDBNK-S", unknown, at)), a.toString())
        }
    }

    @Test
    fun `parsed, dropped, promotional and personal messages are never offered`() = runBlocking<Unit> {
        assertEquals(SmsIntakeResult.Accepted, intake.accept(user, SmsMessage("AX-HDFCBK-S", "Sent Rs.249.00 From HDFC Bank A/C *1234 To SWIGGY On 16/10/26", at)))
        intake.accept(user, SmsMessage("AD-FEDBNK-S", "OTP 482913 for your txn of Rs 500.00", at))
        intake.accept(user, SmsMessage("AD-FEDBNK-S", "Txn of Rs 1,200.00 at CROMA declined", at))
        intake.accept(user, SmsMessage("AD-FEDBNK-P", unknown, at))
        intake.accept(user, SmsMessage("+919800000012", unknown, at))
        intake.accept(user, SmsMessage("JD-YESBNK-T", "Your EMI of Rs 4,500.00 is due on 05-11-26.", at))
        assertTrue(offered.isEmpty(), offered.toString())
    }

    @Test
    fun `without a fallback the intake behaves as before`() = runBlocking<Unit> {
        val plain = SmsIntake(SmsParserRegistry(), IngestEventService(events), mandates)
        assertEquals(SmsIntakeResult.Ignored(SmsParseResult.UNRECOGNISED), plain.accept(user, SmsMessage("AD-FEDBNK-S", unknown, at)))
    }

    @Test
    fun `the raw sms route reports messages queued for reading`() {
        val jwt = JwtService(AppConfig.load().auth)
        val devices = DeviceTokenService(InMemoryDeviceTokenRepository(), jwt)
        val token = runBlocking { devices.issue(user, "Mac", setOf(Scopes.INGEST_SMS)).token }
        testApplication {
            environment { config = MapApplicationConfig() }
            application {
                configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwt); configureDeviceAuthentication(devices)
                routing { route("/api/v1") { rawSmsRoutes(intake) } }
            }
            val res = client.post("/api/v1/ingest/sms/raw") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"messages":[{"sender":"AD-FEDBNK-S","text":"$unknown","receivedAt":"2026-10-16T10:30:00+05:30"}]}""")
            }
            assertEquals(HttpStatusCode.Accepted, res.status)
            val body = res.bodyAsText()
            assertTrue("\"reading\":1" in body && "\"status\":\"reading\"" in body && "ZEPTO" !in body, body)
        }
    }

    @Test
    fun `an unrecognised whatsapp bank message is offered and a friend's never is`() = runBlocking<Unit> {
        val clock = Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"), ZoneOffset.UTC)
        val service = WhatsAppImportService(SmsParserRegistry(), IngestEventService(events, clock), mandates, UserCalendar(InMemoryUserRepository()), clock, sink)
        val chat = """
            16/10/26, 10:30 - HDFC Bank: $unknown
            16/10/26, 10:31 - Federal Bank: $unknown
            16/10/26, 10:32 - Rohit: Rs 500 charged for dinner, pay me back
            16/10/26, 10:33 - Federal Bank: Your KYC is up to date.
        """.trimIndent()
        val result = service.import(user, chat.toByteArray())
        assertEquals(3, result.reading, "the no-money-word message is still offered; the fallback says no")
        assertEquals(listOf("hdfc", null), offered.take(2).map { it.bankCode })
        assertTrue(offered.all { it.channel == MessageChannel.WHATSAPP })
        assertEquals(Instant.parse("2026-10-16T05:00:00Z"), offered.first().receivedAt)

        offered.clear()
        answer = FallbackOffer.Busy
        val again = service.import(user, "17/10/26, 09:00 - Federal Bank: $unknown".toByteArray())
        assertEquals(0, again.reading)
        assertEquals(mapOf(SmsParseResult.UNRECOGNISED to 1), again.ignored)
    }
}
