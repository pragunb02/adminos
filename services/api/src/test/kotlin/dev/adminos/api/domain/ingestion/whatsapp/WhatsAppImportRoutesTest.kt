package dev.adminos.api.domain.ingestion.whatsapp

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.mandates.InMemoryMandateRepository
import dev.adminos.api.domain.financial.mandates.MandateService
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.sms.SmsParserRegistry
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WhatsAppImportRoutesTest {
    private val jwt = JwtService(AppConfig.load().auth)
    private val user = User(id = UUID.randomUUID(), email = "me@example.com", googleId = "g-1", role = UserRole.OWNER)
    private val token = jwt.generateAccessToken(user)
    private val service = WhatsAppImportService(
        SmsParserRegistry(), IngestEventService(InMemoryIngestEventRepository()),
        MandateService(InMemoryMandateRepository(), InMemoryTransactionRepository()), UserCalendar(InMemoryUserRepository())
    )

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwt)
            routing { route("/api/v1") { whatsAppImportRoutes(service) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.upload(content: ByteArray?, auth: Boolean = true, extra: String? = null) =
        client.post("/api/v1/imports/whatsapp") {
            if (auth) cookie(ACCESS_TOKEN_COOKIE, token)
            setBody(MultiPartFormDataContent(formData {
                if (content != null) append("file", content, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"_chat.txt\"")
                })
                if (extra != null) append(extra, "smuggled")
            }))
        }

    @Test
    fun `an unexpected form field is refused before the file is read (AOS-618)`() = routeTest {
        val res = upload("16/10/26, 09:12 - HDFC Bank: hi".toByteArray(), extra = "note")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue("UPLOAD_001" in res.bodyAsText())
    }

    @Test
    fun `upload returns the import counts`() = routeTest {
        val res = upload("16/10/26, 09:12 - HDFC Bank: Sent Rs.249.00 From HDFC Bank A/C *1234 To SWIGGY".toByteArray())
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val body = res.bodyAsText()
        assertTrue("\"accepted\":1" in body && "\"fromBanks\":1" in body && "\"messages\":1" in body, body)
    }

    @Test
    fun `missing file, missing auth, and an unreadable file`() = routeTest {
        assertEquals(HttpStatusCode.BadRequest, upload(null).status)
        assertEquals(HttpStatusCode.Unauthorized, upload("x".toByteArray(), auth = false).status)
        val bad = upload(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 9, 9))
        assertEquals(HttpStatusCode.UnprocessableEntity, bad.status)
        assertTrue("WHATSAPP_UNREADABLE" in bad.bodyAsText())
    }

    @Test
    fun `chat text over the cap is 413`() = routeTest {
        val res = upload(ByteArray(WhatsAppExportFile.MAX_TEXT_BYTES + 1) { 'a'.code.toByte() })
        assertEquals(HttpStatusCode.PayloadTooLarge, res.status)
        assertTrue("FILE_TOO_LARGE" in res.bodyAsText())
    }
}
