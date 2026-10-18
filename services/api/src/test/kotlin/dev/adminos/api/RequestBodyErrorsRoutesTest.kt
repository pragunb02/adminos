package dev.adminos.api

import dev.adminos.api.InfraIngestTestKit.tokenFor
import dev.adminos.api.InfraIngestTestKit.user
import dev.adminos.api.domain.identity.uniqueTestIp
import dev.adminos.api.infrastructure.plugins.ACCESS_TOKEN_COOKIE
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AOS-633: a body that isn't JSON, or isn't valid JSON, on a route that reads JSON is the client's error
 * (415 or 400), never a 500, and the answer never echoes the input or names a class (AUD-15).
 * Runs the real `Application.module`, so every plugin and the production route wiring are in play.
 */
class RequestBodyErrorsRoutesTest {

    /** The owner's address for the sign-in route (AOS-614); a synthetic one, no personal data. */
    private val config = InfraIngestTestKit.config.let { it.copy(auth = it.auth.copy(ownerEmail = "owner@example.com")) }
    private val token = tokenFor(user())

    /** A marker that must never come back in a response: the input and markup an attacker might send. */
    private val canary = "zq-canary-7f3a<script>alert(1)</script>"

    private enum class Body(val contentType: ContentType?, val text: String?) {
        FORM(ContentType.Application.FormUrlEncoded, "amount=120&note=zq-canary-7f3a%3Cscript%3E"),
        TEXT(ContentType.Text.Plain, "zq-canary-7f3a<script>alert(1)</script>"),
        MISSING(null, null),
        EMPTY_JSON(ContentType.Application.Json, ""),
        MALFORMED_JSON(ContentType.Application.Json, """{"clientRef":"zq-canary-7f3a<script>alert(1)</script>","amount":12abc"""),
        WRONG_TYPE_JSON(ContentType.Application.Json, """["zq-canary-7f3a<script>alert(1)</script>"]"""),
        ODD_CONTENT_TYPE(ContentType("zq-canary-7f3a", "x<script>"), "zq-canary-7f3a"),
    }

    private data class JsonRoute(val method: HttpMethod, val path: String, val signedIn: Boolean)

    private val routes = listOf(
        JsonRoute(HttpMethod.Post, "/api/v1/transactions/cash", signedIn = true),
        JsonRoute(HttpMethod.Patch, "/api/v1/users/me/notification-preferences", signedIn = true),
        JsonRoute(HttpMethod.Post, "/api/v1/push/subscriptions", signedIn = true),
        JsonRoute(HttpMethod.Post, "/api/v1/ingest/events", signedIn = true),
        JsonRoute(HttpMethod.Post, "/api/v1/auth/google", signedIn = false), // public: the sign-in call itself
    )

    private fun appTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { this.config = MapApplicationConfig() }
        application { module(this@RequestBodyErrorsRoutesTest.config) }
        block()
    }

    private suspend fun ApplicationTestBuilder.send(route: JsonRoute, body: Body, signedIn: Boolean = route.signedIn): HttpResponse =
        client.request(route.path) {
            method = route.method
            header("X-Forwarded-For", uniqueTestIp()) // the sign-in rate limiter never couples cases
            header("Sec-Fetch-Site", "same-origin") // what the web app's browser sends, so a CSRF check (D-100) passes
            if (signedIn) cookie(ACCESS_TOKEN_COOKIE, token)
            body.contentType?.let { contentType(it) }
            body.text?.let { setBody(it) }
        }

    private suspend fun HttpResponse.error(): JsonObject =
        Json.parseToJsonElement(bodyAsText()).jsonObject["error"]!!.jsonObject

    /** The status and code of a rejected body, and that nothing of the input or the internals leaks. */
    private suspend fun ApplicationTestBuilder.assertRejected(body: Body, status: HttpStatusCode, code: String) {
        for (route in routes) {
            val res = send(route, body)
            val text = res.bodyAsText()
            val where = "${route.method.value} ${route.path} with $body"
            assertEquals(status, res.status, "$where gave $text")
            assertEquals(code, res.error()["code"]!!.jsonPrimitive.content, where)
            for (leak in listOf("zq-canary", "script", "12abc", "dev.adminos", "kotlinx", "io.ktor", "Exception", "offset", "JSON input")) {
                assertFalse(leak in text, "$where leaked '$leak': $text")
            }
            assertTrue(Json.parseToJsonElement(text).jsonObject["meta"]!!.jsonObject["requestId"]!!.jsonPrimitive.content.startsWith("req_"), where)
        }
    }

    @Test
    fun `a form body is 415 with a generic message on every JSON route`() = appTest {
        assertRejected(Body.FORM, HttpStatusCode.UnsupportedMediaType, "VALIDATION_005")
    }

    @Test
    fun `a plain text body is 415 with a generic message on every JSON route`() = appTest {
        assertRejected(Body.TEXT, HttpStatusCode.UnsupportedMediaType, "VALIDATION_005")
    }

    @Test
    fun `a missing body is 415 on every JSON route`() = appTest {
        assertRejected(Body.MISSING, HttpStatusCode.UnsupportedMediaType, "VALIDATION_005")
    }

    @Test
    fun `an odd Content-Type is 415 and never echoed`() = appTest {
        assertRejected(Body.ODD_CONTENT_TYPE, HttpStatusCode.UnsupportedMediaType, "VALIDATION_005")
    }

    @Test
    fun `an empty JSON body is 400 malformed on every JSON route`() = appTest {
        assertRejected(Body.EMPTY_JSON, HttpStatusCode.BadRequest, "VALIDATION_004")
    }

    @Test
    fun `malformed JSON is 400 and never echoes the input`() = appTest {
        assertRejected(Body.MALFORMED_JSON, HttpStatusCode.BadRequest, "VALIDATION_004")
    }

    @Test
    fun `JSON of the wrong shape is 400 and never echoes the input`() = appTest {
        assertRejected(Body.WRONG_TYPE_JSON, HttpStatusCode.BadRequest, "VALIDATION_004")
    }

    @Test
    fun `a form body without a session is 401 before the body is read`() = appTest {
        for (route in routes.filter { it.signedIn }) {
            val res = send(route, Body.FORM, signedIn = false)
            assertEquals(HttpStatusCode.Unauthorized, res.status, "${route.method.value} ${route.path}")
        }
    }

    @Test
    fun `a form body with a charset parameter is still 415`() = appTest {
        val res = client.post("/api/v1/transactions/cash") {
            cookie(ACCESS_TOKEN_COOKIE, token)
            header("Sec-Fetch-Site", "same-origin")
            contentType(ContentType.Application.FormUrlEncoded.withCharset(Charsets.UTF_8))
            setBody("amount=120")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, res.status)
    }

    @Test
    fun `a valid JSON body still works after the mapping`() = appTest {
        val res = client.post("/api/v1/transactions/cash") {
            cookie(ACCESS_TOKEN_COOKIE, token)
            header("Sec-Fetch-Site", "same-origin")
            contentType(ContentType.Application.Json)
            setBody("""{"clientRef":"cash-0633-abcd","amount":"120","category":"food","note":"Chai"}""")
        }
        assertEquals(HttpStatusCode.Accepted, res.status, res.bodyAsText())
    }
}
