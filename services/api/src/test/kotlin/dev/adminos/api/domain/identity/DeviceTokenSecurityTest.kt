package dev.adminos.api.domain.identity

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.InMemoryAuditRepository
import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.sms.SmsIntake
import dev.adminos.api.domain.ingestion.sms.SmsParserRegistry
import dev.adminos.api.domain.ingestion.sms.rawSmsRoutes
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.response.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

/** AOS-203 device tokens: scoped, revocable, never interchangeable with login tokens. */
class DeviceTokenSecurityTest {

    private val config = AppConfig.load()
    private val jwt = JwtService(config.auth)
    private val tokens = InMemoryDeviceTokenRepository()
    private val service = DeviceTokenService(tokens, jwt)
    private val events = InMemoryIngestEventRepository()
    private val user = User(id = UUID.randomUUID(), email = "me@example.com", googleId = "g-1", role = UserRole.OWNER)
    private val accessJwt = jwt.generateAccessToken(user)

    private fun issue(): IssuedDeviceToken = runBlocking { service.issue(user.id, "My Mac", setOf(Scopes.INGEST_SMS)) }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwt)
            configureDeviceAuthentication(service)
            routing {
                route("/api/v1") {
                    deviceTokenRoutes(service)
                    rawSmsRoutes(SmsIntake(SmsParserRegistry(), IngestEventService(events)))
                    authenticate("auth-jwt") { get("/me") { call.respondText(call.userPrincipal.userId.toString()) } }
                }
            }
        }
        block()
    }

    private val sms = """{"messages":[{"sender":"AX-HDFCBK-S","text":"Sent Rs.249.00 From HDFC Bank A/C *1234 To SWIGGY On 01/10/26 Ref 123456789012","receivedAt":"2026-10-01T09:12:00+05:30"}]}"""

    private suspend fun ApplicationTestBuilder.postSms(bearer: String?, body: String = sms) = client.post("/api/v1/ingest/sms/raw") {
        bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        contentType(ContentType.Application.Json); setBody(body)
    }

    // ── JWT type separation ──

    @Test
    fun `a device token is not a login token and a login token is not a device token`() {
        val device = issue().token
        assertNull(jwt.verifyAccessToken(device), "device token must not authenticate as a user session")
        assertNull(jwt.verifyDeviceToken(accessJwt), "access token must not authenticate as a device")
        assertNotNull(jwt.verifyDeviceToken(device))
    }

    @Test
    fun `tokens from another issuer are rejected`() {
        val other = JwtService(config.auth.copy(jwtIssuer = "someone-else"))
        assertNull(jwt.verifyAccessToken(other.generateAccessToken(user)))
        assertNull(jwt.verifyDeviceToken(other.generateDeviceToken(user.id, UUID.randomUUID(), setOf(Scopes.INGEST_SMS), Duration.ofDays(1))))
    }

    // ── Service ──

    @Test
    fun `revoked, expired, wrong-scope and unknown tokens do not authenticate`() = runBlocking<Unit> {
        val issued = issue()
        assertNotNull(service.authenticate(issued.token, Scopes.INGEST_SMS))
        assertNull(service.authenticate(issued.token, "read:transactions"), "scope not granted")

        assertTrue(service.revoke(user.id, issued.record.id))
        assertNull(service.authenticate(issued.token, Scopes.INGEST_SMS), "revoked")

        val unknownRow = jwt.generateDeviceToken(user.id, UUID.randomUUID(), setOf(Scopes.INGEST_SMS), Duration.ofDays(1))
        assertNull(service.authenticate(unknownRow, Scopes.INGEST_SMS), "no row")

        val later = DeviceTokenService(tokens, jwt, Clock.fixed(Instant.now().plus(Duration.ofDays(400)), ZoneOffset.UTC))
        assertNull(later.authenticate(issue().token, Scopes.INGEST_SMS), "expired row")
    }

    @Test
    fun `users can only revoke their own tokens, and use is recorded`() = runBlocking<Unit> {
        val issued = issue()
        assertFalse(service.revoke(UUID.randomUUID(), issued.record.id))
        service.authenticate(issued.token, Scopes.INGEST_SMS)
        assertNotNull(tokens.findById(issued.record.id)!!.lastUsedAt)
    }

    @Test
    fun `unknown scopes cannot be issued`() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { service.issue(user.id, "x", setOf("admin")) }
    }

    // ── HTTP ──

    @Test
    fun `device token ingests sms and the text is not stored`() = app {
        val res = postSms(issue().token)
        assertEquals(HttpStatusCode.Accepted, res.status, res.bodyAsText())
        assertTrue("\"accepted\":1" in res.bodyAsText())
        val stored = runBlocking { events.findByUser(user.id) }.single()
        assertEquals("SWIGGY", stored.merchantRaw)
        assertTrue("Ref 123456789012" !in stored.toString())
    }

    @Test
    fun `login sessions cannot use the device endpoint, and devices cannot use login endpoints`() = app {
        assertEquals(HttpStatusCode.Unauthorized, postSms(accessJwt).status, "user JWT as bearer")
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/ingest/sms/raw") {
            cookie(ACCESS_TOKEN_COOKIE, accessJwt); contentType(ContentType.Application.Json); setBody(sms)
        }.status, "browser cookie")
        val device = issue().token
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { header(HttpHeaders.Authorization, "Bearer $device") }.status)
        assertEquals(HttpStatusCode.Unauthorized, postSms(null).status)
    }

    @Test
    fun `revoked token is rejected over http`() = app {
        val issued = issue()
        runBlocking { service.revoke(user.id, issued.record.id) }
        assertEquals(HttpStatusCode.Unauthorized, postSms(issued.token).status)
    }

    @Test
    fun `malformed bodies get a 400 that never echoes the sms text`() = app {
        val secret = "Sent Rs.999.00 To SECRETSHOP"
        for (body in listOf("""{"messages":[{"sender":"AX-HDFCBK-S","text":"$secret"}]}""", """{"messages":"$secret"}""", "not json $secret")) {
            val res = postSms(issue().token, body)
            assertEquals(HttpStatusCode.BadRequest, res.status)
            assertTrue("SECRETSHOP" !in res.bodyAsText(), "error echoed the text: ${res.bodyAsText()}")
        }
    }

    @Test
    fun `token management - create shows the token once, list never does, revoke works`() = app {
        val created = client.post("/api/v1/device-tokens") {
            cookie(ACCESS_TOKEN_COOKIE, accessJwt); contentType(ContentType.Application.Json); setBody("""{"name":"My Mac"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val data = Json.parseToJsonElement(created.bodyAsText()).jsonObject["data"]!!.jsonObject
        val id = data["id"]!!.jsonPrimitive.content
        assertTrue(data["token"]!!.jsonPrimitive.content.count { it == '.' } == 2, "a JWT")

        val list = client.get("/api/v1/device-tokens") { cookie(ACCESS_TOKEN_COOKIE, accessJwt) }.bodyAsText()
        assertTrue(id in list && "\"token\":\"" !in list, "list must not contain the secret")

        assertEquals(HttpStatusCode.OK, client.delete("/api/v1/device-tokens/$id") { cookie(ACCESS_TOKEN_COOKIE, accessJwt) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/device-tokens/${UUID.randomUUID()}") { cookie(ACCESS_TOKEN_COOKIE, accessJwt) }.status)
    }
}
