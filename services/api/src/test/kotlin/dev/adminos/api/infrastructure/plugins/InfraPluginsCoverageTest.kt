package dev.adminos.api.infrastructure.plugins

import dev.adminos.api.InfraIngestTestKit.jwtService
import dev.adminos.api.InfraIngestTestKit.tokenFor
import dev.adminos.api.InfraIngestTestKit.user
import dev.adminos.api.domain.financial.links.LinkKind
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.config.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Production Ktor plugins (AOS-312): error mapping, request ids, auth principal, query params, rate limiting, CORS. */
class InfraPluginsCoverageTest {

    /** An exception whose cause is itself, as some libraries report. */
    private class SelfCaused : RuntimeException("self") {
        override val cause: Throwable get() = this
    }

    private fun pluginTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            routing {
                get("/iae") { throw IllegalArgumentException("amount: must be more than 0") }
                get("/iae-no-message") { throw IllegalArgumentException() }
                get("/date-parse") { call.respondText(LocalDate.parse("16-10-2026").toString()) }
                get("/zone") { call.respondText(ZoneId.of("Mars/Olympus").id) }
                get("/boom") { throw IllegalStateException("db password=hunter2 at 10.0.0.5") }
                get("/bad-body-chain") { throw BadRequestException("outer", RuntimeException("mid", RuntimeException("field 'amount' expected string\nat line 2"))) }
                get("/bad-body-no-message") { throw BadRequestException("outer", RuntimeException()) }
                get("/bad-body-self-cause") { throw BadRequestException("outer", SelfCaused()) }
                get("/request-id") { call.respondText(call.requestId) }
                get("/zone-anon") { call.respondText(call.userZone.id) }
                get("/principal-anon") { call.respondText(call.userPrincipal.userId.toString()) }
                authenticate("auth-jwt") { get("/zone-auth") { call.respondText(call.userZone.id) } }
                get("/params") {
                    val out = buildJsonObject {
                        put("kind", call.enumParam<LinkKind>("kind")?.name)
                        put("flag", call.booleanParam("flag"))
                        put("id", call.uuidParam("id")?.toString())
                        put("limit", call.limitParam(default = 20, max = 50))
                        put("from", call.rangeStartParam("from")?.toString())
                        put("to", call.rangeEndParam("to")?.toString())
                        put("day", call.dateParam("day")?.toString())
                    }
                    call.respondText(out.toString(), ContentType.Application.Json)
                }
            }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.error(path: String): Pair<HttpStatusCode, JsonObject> {
        val res = client.get(path)
        return res.status to Json.parseToJsonElement(res.bodyAsText()).jsonObject["error"]!!.jsonObject
    }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    // ── Error handling ──

    @Test
    fun `IllegalArgumentException is a 400 with its message`() = pluginTest {
        val (status, err) = error("/iae")
        assertEquals(HttpStatusCode.BadRequest, status)
        assertEquals("VALIDATION_001", err.str("code"))
        assertEquals("amount: must be more than 0", err.str("message"))
    }

    @Test
    fun `IllegalArgumentException without a message gets a generic one`() = pluginTest {
        assertEquals("Invalid input", error("/iae-no-message").second.str("message"))
    }

    @Test
    fun `DateTimeParseException is a 400 VALIDATION_002`() = pluginTest {
        val (status, err) = error("/date-parse")
        assertEquals(HttpStatusCode.BadRequest, status)
        assertEquals("VALIDATION_002", err.str("code"))
    }

    @Test
    fun `an invalid time zone is a 400 VALIDATION_003`() = pluginTest {
        val (status, err) = error("/zone")
        assertEquals(HttpStatusCode.BadRequest, status)
        assertEquals("VALIDATION_003", err.str("code"))
    }

    @Test
    fun `unexpected exceptions are a 500 that does not leak the exception message`() = pluginTest {
        val res = client.get("/boom")
        assertEquals(HttpStatusCode.InternalServerError, res.status)
        val body = res.bodyAsText()
        assertFalse("hunter2" in body || "10.0.0.5" in body || "IllegalStateException" in body, body)
    }

    @Test
    fun `malformed body reports only the first line of the innermost cause`() = pluginTest {
        val (status, err) = error("/bad-body-chain")
        assertEquals(HttpStatusCode.BadRequest, status)
        assertEquals("Malformed request body: field 'amount' expected string", err.str("message"))
    }

    @Test
    fun `malformed body with a message-less cause says invalid`() = pluginTest {
        assertEquals("Malformed request body: invalid", error("/bad-body-no-message").second.str("message"))
    }

    @Test
    fun `a self-referencing cause chain terminates`() = pluginTest {
        assertEquals("Malformed request body: self", error("/bad-body-self-cause").second.str("message"))
    }

    // ── Request id ──

    @Test
    fun `request ids are generated server-side and a client-supplied id is ignored`() = pluginTest {
        val id = client.get("/request-id") { header("X-Request-Id", "attacker-chosen") }.bodyAsText()
        assertTrue(Regex("req_[0-9a-f]{12}").matches(id), id)
    }

    @Test
    fun `requestId falls back to req_unknown without the CallId plugin`() = testApplication {
        environment { config = MapApplicationConfig() }
        application { routing { get("/id") { call.respondText(call.requestId) } } }
        assertEquals("req_unknown", client.get("/id").bodyAsText())
    }

    // ── Authentication principal ──

    @Test
    fun `userZone is the default zone when nobody is signed in`() = pluginTest {
        assertEquals("Asia/Kolkata", client.get("/zone-anon").bodyAsText())
    }

    @Test
    fun `userZone is the time zone carried in the token`() = pluginTest {
        val res = client.get("/zone-auth") { bearerAuth(tokenFor(user(timezone = "Europe/London"))) }
        assertEquals("Europe/London", res.bodyAsText())
    }

    @Test
    fun `a token without a valid time zone falls back to the default zone`() = pluginTest {
        val res = client.get("/zone-auth") { bearerAuth(tokenFor(user(timezone = "Not/AZone"))) }
        assertEquals("Asia/Kolkata", res.bodyAsText())
    }

    @Test
    fun `userPrincipal outside an authenticated route is a 401`() = pluginTest {
        val (status, err) = error("/principal-anon")
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertEquals("AUTH_002", err.str("code"))
    }

    // ── Query parameters ──

    private suspend fun ApplicationTestBuilder.params(query: String): Pair<HttpStatusCode, JsonObject> {
        val res = client.get("/params$query")
        return res.status to Json.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    @Test
    fun `absent query parameters are null and limit takes its default`() = pluginTest {
        val (status, body) = params("")
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(JsonNull, body["kind"]); assertEquals(JsonNull, body["flag"]); assertEquals(JsonNull, body["id"])
        assertEquals(20, body["limit"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, body["from"]); assertEquals(JsonNull, body["to"]); assertEquals(JsonNull, body["day"])
    }

    @Test
    fun `valid query parameters are parsed`() = pluginTest {
        val (status, body) = params("?kind=Suggested&flag=true&id=7d0c6c39-6a0a-4a65-8f3f-2f1b0f1a9a11&limit=50&from=2026-10-01&to=2026-10-31&day=2026-10-16")
        assertEquals(HttpStatusCode.OK, status, body.toString())
        assertEquals("SUGGESTED", body.str("kind"))
        assertEquals(true, body["flag"]!!.jsonPrimitive.boolean)
        assertEquals("7d0c6c39-6a0a-4a65-8f3f-2f1b0f1a9a11", body.str("id"))
        assertEquals(50, body["limit"]!!.jsonPrimitive.int)
        assertEquals("2026-09-30T18:30:00Z", body.str("from"), "start of the day in IST")
        assertEquals("2026-10-31T18:29:59.999999999Z", body.str("to"), "end of the day in IST")
        assertEquals("2026-10-16", body.str("day"))
    }

    @Test
    fun `instants are accepted for range parameters`() = pluginTest {
        val (_, body) = params("?from=2026-10-01T00:00:00Z&to=2026-10-02T00:00:00Z")
        assertEquals("2026-10-01T00:00:00Z", body.str("from"))
        assertEquals("2026-10-02T00:00:00Z", body.str("to"))
    }

    @Test
    fun `every invalid query parameter is a 400 naming it`() = pluginTest {
        for ((query, name) in listOf(
            "?kind=maybe" to "kind", "?flag=yes" to "flag", "?id=not-a-uuid" to "id", "?limit=abc" to "limit",
            "?limit=0" to "limit", "?limit=51" to "limit", "?from=yesterday" to "from", "?to=16/10/2026" to "to", "?day=2026-13-01" to "day"
        )) {
            val res = client.get("/params$query")
            assertEquals(HttpStatusCode.BadRequest, res.status, query)
            assertTrue("'$name'" in res.bodyAsText(), "$query -> ${res.bodyAsText()}")
        }
    }

    // ── Rate limiting ──

    @Test
    fun `rate limit plugin answers 429 with retryAfterSeconds once the limit is used up`() = testApplication {
        val limiter = RateLimiter(maxRequests = 1, windowSeconds = 60)
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId()
            routing {
                route("/limited") {
                    install(RateLimitPlugin) { this.limiter = limiter }
                    get { call.respondText("ok") }
                }
            }
        }
        assertEquals(HttpStatusCode.OK, client.get("/limited").status)
        val res = client.get("/limited")
        assertEquals(HttpStatusCode.TooManyRequests, res.status)
        val err = Json.parseToJsonElement(res.bodyAsText()).jsonObject["error"]!!.jsonObject
        assertEquals("RATE_001", err.str("code"))
        assertTrue(err["details"]!!.jsonObject["retryAfterSeconds"]!!.jsonPrimitive.long in 1..60)
    }

    @Test
    fun `an unknown key has nothing to wait for and its full allowance`() {
        val limiter = RateLimiter(maxRequests = 3, windowSeconds = 60)
        assertEquals(0, limiter.retryAfterSeconds("203.0.113.9"))
        assertEquals(3, limiter.remainingRequests("203.0.113.9"))
    }

    @Test
    fun `an expired window starts over`() {
        val limiter = RateLimiter(maxRequests = 1, windowSeconds = -1) // every window is already expired
        assertTrue(limiter.isAllowed("203.0.113.9"))
        assertTrue(limiter.isAllowed("203.0.113.9"))
    }

    @Test
    fun `more than 1000 tracked keys evicts the expired windows`() {
        val limiter = RateLimiter(maxRequests = 1, windowSeconds = -1)
        repeat(1001) { limiter.isAllowed("10.0.${it / 256}.${it % 256}") }
        limiter.isAllowed("203.0.113.9")
        assertEquals(1, limiter.remainingRequests("10.0.0.0"), "evicted, so a fresh allowance")
    }

    @Test
    fun `more than 1000 tracked keys keeps windows that are still live`() {
        val limiter = RateLimiter(maxRequests = 1, windowSeconds = 3600)
        repeat(1001) { limiter.isAllowed("10.0.${it / 256}.${it % 256}") }
        limiter.isAllowed("203.0.113.9")
        assertEquals(0, limiter.remainingRequests("10.0.0.0"), "still counted")
    }

    // ── CORS ──

    private fun corsTest(appUrl: String, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application { configureHTTP(appUrl); routing { post("/x") { call.respondText("ok") } } }
        block()
    }

    @Test
    fun `CORS allows the configured http origin`() = corsTest("http://localhost:3000") {
        val res = client.options("/x") { header(HttpHeaders.Origin, "http://localhost:3000"); header(HttpHeaders.AccessControlRequestMethod, "POST") }
        assertEquals("http://localhost:3000", res.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `CORS rejects a look-alike subdomain of the app origin`() = corsTest("http://localhost:3000") {
        val res = client.options("/x") { header(HttpHeaders.Origin, "http://evil.localhost:3000"); header(HttpHeaders.AccessControlRequestMethod, "POST") }
        assertNull(res.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `CORS allows the configured https origin`() = corsTest("https://app.adminos.in") {
        val res = client.options("/x") { header(HttpHeaders.Origin, "https://app.adminos.in"); header(HttpHeaders.AccessControlRequestMethod, "POST") }
        assertEquals("https://app.adminos.in", res.headers[HttpHeaders.AccessControlAllowOrigin])
    }
}
