package dev.adminos.api.domain.wealth.imports

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.domain.wealth.Quantity
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** /api/v1/wealth/imports contract (AOS-805): upload, list, undo, reconciliation; auth, CSRF, ownership, form shape. */
class WealthImportRoutesTest {
    private val config = AppConfig.load()
    private val jwt = JwtService(config.auth)
    private val kit = CasImportServiceTest.Kit()
    private val owner = User(id = UUID.randomUUID(), email = "owner@example.com", googleId = "g-1", role = UserRole.OWNER)
    private val other = User(id = UUID.randomUUID(), email = "other@example.com", googleId = "g-2", role = UserRole.OWNER)

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { this.config = MapApplicationConfig() }
        application {
            configureRequestId(); configureRequestBodyLimit(); configureSerialization(); configureHTTP(config.app.appUrl)
            configureErrorHandling(); configureAuthentication(jwt)
            routing { route("/api/v1") { wealthImportRoutes(kit.service) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.upload(who: User? = owner, sameOrigin: Boolean = true, form: FormBuilder.() -> Unit = {
        append("password", CasFixtures.PASSWORD)
        append("file", "%PDF-1.4 synthetic".toByteArray(), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"My CAS 9000.pdf\"") })
    }) = client.submitFormWithBinaryData("/api/v1/wealth/imports/cas", formData(form)) {
        if (who != null) cookie(ACCESS_TOKEN_COOKIE, jwt.generateAccessToken(who))
        if (sameOrigin) header("Sec-Fetch-Site", "same-origin")
    }

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, path: String, who: User = owner) = client.request(path) {
        this.method = method
        cookie(ACCESS_TOKEN_COOKIE, jwt.generateAccessToken(who))
        if (method != HttpMethod.Get) header("Sec-Fetch-Site", "same-origin")
    }

    private fun HttpResponse.json() = runCatching { Json.parseToJsonElement(kotlinx.coroutines.runBlocking { bodyAsText() }).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    private fun JsonObject.data() = getValue("data").jsonObject
    private fun JsonObject.code() = getValue("error").jsonObject.getValue("code").jsonPrimitive.content

    @Test
    fun `an upload is imported and each holding's reconciliation comes back`() = routeTest {
        val res = upload()
        assertEquals(HttpStatusCode.Created, res.status, res.bodyAsText())
        val data = res.json().data()
        assertEquals(8, data.getValue("batch").jsonObject.getValue("added").jsonPrimitive.int)
        assertEquals(0, data.getValue("blocked").jsonPrimitive.int)
        val first = data.getValue("holdings").jsonArray.first().jsonObject
        assertEquals("••••4/56", first.getValue("folio").jsonPrimitive.content)
        assertEquals("reconciled", first.getValue("status").jsonPrimitive.content)
        assertEquals("150", first.getValue("statementUnits").jsonPrimitive.content)
        assertFalse("90001234" in res.bodyAsText(), "the full folio is never returned")
    }

    @Test
    fun `imports are listed, undone once, and someone else's import is not found`() = routeTest {
        val id = upload().json().data().getValue("batch").jsonObject.getValue("id").jsonPrimitive.content
        val list = call(HttpMethod.Get, "/api/v1/wealth/imports").json().data().getValue("imports").jsonArray
        assertEquals(listOf(id), list.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(0, call(HttpMethod.Get, "/api/v1/wealth/imports", other).json().data().getValue("imports").jsonArray.size)

        val stranger = call(HttpMethod.Post, "/api/v1/wealth/imports/$id/undo", other)
        val missing = call(HttpMethod.Post, "/api/v1/wealth/imports/${UUID.randomUUID()}/undo")
        assertEquals(HttpStatusCode.NotFound, stranger.status)
        assertEquals(missing.json().code(), stranger.json().code(), "another user's import looks exactly like a missing one")
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/api/v1/wealth/imports/not-a-uuid/undo").status)

        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/api/v1/wealth/imports/$id/undo").status)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/api/v1/wealth/imports/$id/undo").status)
        assertEquals("undone", call(HttpMethod.Get, "/api/v1/wealth/imports").json().data().getValue("imports").jsonArray.single().jsonObject.getValue("status").jsonPrimitive.content)
    }

    @Test
    fun `reconciliation lists blocked holdings first, and nothing of the owner's for anyone else`() = routeTest {
        kit.next = { CasFixtures.withFlexi { it.copy(closingUnits = Quantity.parse("151")) } }
        upload()
        val body = call(HttpMethod.Get, "/api/v1/wealth/reconciliation").json().data()
        assertEquals(1, body.getValue("blocked").jsonPrimitive.int)
        val first = body.getValue("holdings").jsonArray.first().jsonObject
        assertEquals("blocked", first.getValue("status").jsonPrimitive.content)
        assertTrue(first.getValue("reason").jsonPrimitive.content.startsWith("Units don't add up"))
        val theirs = call(HttpMethod.Get, "/api/v1/wealth/reconciliation", other).json().data()
        assertEquals(0, theirs.getValue("holdings").jsonArray.size)
    }

    @Test
    fun `parser refusals keep their code and an unreachable parser is 503`() = routeTest {
        kit.next = { throw CasParseException("PASSWORD_INCORRECT", "That password doesn't open this statement", clientError = true) }
        val wrong = upload()
        assertEquals(HttpStatusCode.UnprocessableEntity, wrong.status)
        assertEquals("PASSWORD_INCORRECT", wrong.json().code())
        assertFalse(CasFixtures.PASSWORD in wrong.bodyAsText())
        kit.next = { throw CasParseException("PARSER_UNAVAILABLE", "The statement parser isn't reachable", clientError = false) }
        assertEquals(HttpStatusCode.ServiceUnavailable, upload().status)
    }

    @Test
    fun `the form must be exactly a file and a password`() = routeTest {
        assertEquals("WEALTH_002", upload { append("password", "x") }.json().code())
        assertEquals("UPLOAD_001", upload { append("note", "<script>alert(1)</script>") }.json().code())
        assertEquals("UPLOAD_001", upload {
            append("file", "%PDF-1".toByteArray(), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"a.pdf\"") })
            append("file", "%PDF-2".toByteArray(), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"b.pdf\"") })
        }.json().code())
        assertEquals("UPLOAD_001", upload { append("password", "p".repeat(257)) }.json().code())
    }

    @Test
    fun `signed out, forged and cross-site uploads are refused`() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, upload(who = null).status)
        val forged = client.submitFormWithBinaryData("/api/v1/wealth/imports/cas", formData { append("password", "x") }) {
            cookie(ACCESS_TOKEN_COOKIE, "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.forged"); header("Sec-Fetch-Site", "same-origin")
        }
        assertEquals(HttpStatusCode.Unauthorized, forged.status)
        assertEquals(HttpStatusCode.Forbidden, upload(sameOrigin = false).status, "a cookie mutation without same-origin proof (CSRF)")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/wealth/reconciliation").status)
        assertTrue(kit.service.batches(owner.id).isEmpty(), "nothing was imported by the refused requests")
    }
}
