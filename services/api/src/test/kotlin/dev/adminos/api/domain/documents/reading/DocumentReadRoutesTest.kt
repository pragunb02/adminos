package dev.adminos.api.domain.documents.reading

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.documents.documentRoutes
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.install
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** POST /api/v1/documents/{id}/read and saving confirmed fields (AOS-717): auth, ownership, CSRF, limits, errors. */
class DocumentReadRoutesTest {
    private val jwt = JwtService(AppConfig.load().auth)
    private val origin = "http://localhost:3000"

    private fun routeTest(kit: DocumentReadingKit, limiter: RateLimiter = RateLimiters.documentRead(), block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwt)
            install(OriginCheck) { allowedOrigin = origin } // the app-wide CSRF check (AOS-618)
            routing { route("/api/v1") { documentRoutes(kit.documents); documentReadRoutes(kit.service, limiter) } }
        }
        block()
    }

    private fun user(id: UUID) = User(id = id, email = "owner@example.com", googleId = "g-$id")

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, path: String, who: UUID?, withOrigin: Boolean = true, body: String? = null) =
        client.request("/api/v1$path") {
            this.method = method
            who?.let { cookie(ACCESS_TOKEN_COOKIE, jwt.generateAccessToken(user(it))) }
            if (withOrigin) header(HttpHeaders.Origin, origin)
            body?.let { contentType(ContentType.Application.Json); setBody(it) }
        }

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.code() = json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    @Test
    fun `the owner gets proposals, never the full number, and nothing is cached`() {
        val k = DocumentReadingKit()
        val id = runBlocking { k.upload().id }
        routeTest(k) {
            val res = call(HttpMethod.Post, "/documents/$id/read", k.owner)
            assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
            assertEquals("no-store", res.headers[HttpHeaders.CacheControl])
            val data = res.json()["data"]!!.jsonObject
            assertEquals("text_layer", data["source"]!!.jsonPrimitive.content)
            assertEquals("off", data["ai"]!!.jsonPrimitive.content)
            val fields = data["proposals"]!!.jsonArray.associate { it.jsonObject["field"]!!.jsonPrimitive.content to it.jsonObject["value"]!!.jsonPrimitive.content }
            assertEquals(mapOf("expiresOn" to "2027-03-31", "number" to "3210", "amount" to "12500.00"), fields)
            val body = res.bodyAsText()
            listOf("9876543210", "Asha", "Pune", "Insured").forEach { assertFalse(it in body, "$it is never returned") }
        }
    }

    @Test
    fun `signed out, a forged token, a cross-site page, another user and an unknown id are all refused`() {
        val k = DocumentReadingKit()
        val id = runBlocking { k.upload().id }
        routeTest(k) {
            assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Post, "/documents/$id/read", null).status)
            val forged = client.post("/api/v1/documents/$id/read") { cookie(ACCESS_TOKEN_COOKIE, "eyJhbGciOiJub25lIn0.e30."); header(HttpHeaders.Origin, origin) }
            assertEquals(HttpStatusCode.Unauthorized, forged.status)
            val crossSite = call(HttpMethod.Post, "/documents/$id/read", k.owner, withOrigin = false).let { it.status }
            assertEquals(HttpStatusCode.Forbidden, crossSite)
            val evil = client.post("/api/v1/documents/$id/read") { cookie(ACCESS_TOKEN_COOKIE, jwt.generateAccessToken(user(k.owner))); header(HttpHeaders.Origin, "https://evil.example") }
            assertEquals(HttpStatusCode.Forbidden, evil.status)
            val other = call(HttpMethod.Post, "/documents/$id/read", UUID.randomUUID())
            val unknown = call(HttpMethod.Post, "/documents/${UUID.randomUUID()}/read", k.owner)
            val junk = call(HttpMethod.Post, "/documents/..%2F..%2Fetc/read", k.owner)
            for (r in listOf(other, unknown, junk)) { assertEquals(HttpStatusCode.NotFound, r.status); assertEquals("DOC_001", r.code()) }
            assertEquals(other.bodyAsText().substringBefore("requestId"), unknown.bodyAsText().substringBefore("requestId"), "the same 404: ids reveal nothing")
        }
        assertTrue(k.reader.calls.isEmpty(), "nothing was read for a refused request")
    }

    @Test
    fun `reads are rate limited per user`() {
        val k = DocumentReadingKit()
        val id = runBlocking { k.upload().id }
        routeTest(k, limiter = RateLimiter(maxRequests = 2, windowSeconds = 60)) {
            repeat(2) { assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/documents/$id/read", k.owner).status) }
            val third = call(HttpMethod.Post, "/documents/$id/read", k.owner)
            assertEquals(HttpStatusCode.TooManyRequests, third.status)
            assertEquals("RATE_001", third.code())
        }
    }

    @Test
    fun `a file the reader can't read is 422 with its code, the reader down is 503, and neither echoes anything`() {
        val k = DocumentReadingKit()
        val id = runBlocking { k.upload().id }
        routeTest(k) {
            k.reader.answer = { throw DocumentReadException("IMAGE_TOO_LARGE", "The image is too large to read", clientError = true) }
            val bad = call(HttpMethod.Post, "/documents/$id/read", k.owner)
            assertEquals(HttpStatusCode.UnprocessableEntity, bad.status)
            assertEquals("DOC_008", bad.code())
            assertEquals("IMAGE_TOO_LARGE", bad.json()["error"]!!.jsonObject["details"]!!.jsonObject["reason"]!!.jsonPrimitive.content)
            k.reader.answer = { throw DocumentReadException("READER_UNAVAILABLE", "down", clientError = false) }
            val down = call(HttpMethod.Post, "/documents/$id/read", k.owner)
            assertEquals(HttpStatusCode.ServiceUnavailable, down.status)
            assertEquals("DOC_009", down.code())
            assertFalse("down" in down.bodyAsText().substringAfter("message"), "the reader's own words aren't passed through")
        }
    }

    @Test
    fun `confirmed fields are saved by PATCH, validated, and can be removed`() {
        val k = DocumentReadingKit()
        val id = runBlocking { k.upload().id }
        routeTest(k) {
            val saved = call(HttpMethod.Patch, "/documents/$id", k.owner,
                body = """{"expiresOn":"2027-03-31","issuer":"Example Mutual General Insurance Company Limited","numberLast4":"ab12","amount":"12500.00"}""")
            assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
            val d = saved.json()["data"]!!.jsonObject
            assertEquals("AB12", d["numberLast4"]!!.jsonPrimitive.content)
            assertEquals("12500.00", d["amount"]!!.jsonPrimitive.content)
            assertEquals("2027-03-31", d["expiresOn"]!!.jsonPrimitive.content)
            for (bad in listOf("""{"numberLast4":"9876543210"}""", """{"numberLast4":"12-4"}""", """{"amount":"-5"}""", """{"amount":"1e9"}""",
                    """{"amount":"12.345"}""", """{"issuer":"${"x".repeat(121)}"}""")) {
                assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Patch, "/documents/$id", k.owner, body = bad).status, bad)
            }
            val cleared = call(HttpMethod.Patch, "/documents/$id", k.owner, body = """{"issuer":"","numberLast4":"","amount":""}""").json()["data"]!!.jsonObject
            assertTrue(cleared["issuer"] is JsonNull && cleared["numberLast4"] is JsonNull && cleared["amount"] is JsonNull)
            assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Patch, "/documents/$id", UUID.randomUUID(), body = """{"amount":"1"}""").status)
        }
    }
}
