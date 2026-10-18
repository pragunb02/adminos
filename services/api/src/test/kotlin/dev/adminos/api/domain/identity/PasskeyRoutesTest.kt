package dev.adminos.api.domain.identity

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.InMemoryAuditRepository
import dev.adminos.api.domain.ingestion.connection.InMemoryOAuthStateRepository
import dev.adminos.api.domain.ingestion.connection.OAuthStateService
import dev.adminos.api.infrastructure.plugins.*
import dev.adminos.api.infrastructure.webauthn.YubicoWebAuthn
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.auth.authenticate
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.nio.ByteBuffer
import kotlin.test.*

/** The passkey step over HTTP (AOS-617): Google sign-in → pending session → enrol or verify → the app opens. */
class PasskeyRoutesTest {

    private val google = FakeGoogleServer()
    private val config = AppConfig.load().let { it.copy(auth = it.auth.copy(ownerEmail = "owner@example.com", googleClientId = "test-client-id")) }
    private val origin = config.app.appUrl
    private val users = InMemoryUserRepository()
    private val sessions = InMemorySessionRepository()
    private val jwt = JwtService(config.auth)
    private val auth = AuthService(users, sessions, jwt, google.client(config.auth), config.auth)
    private val passkeys = PasskeyService(InMemoryPasskeyRepository(), InMemoryRecoveryCodeRepository(), sessions, users, YubicoWebAuthn(config.app.appUrl))
    private val authenticator = SoftAuthenticator(originOf(config.app.appUrl), URI(config.app.appUrl).host)

    @AfterTest
    fun stop() = google.close()

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { this.config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureHTTP(origin)
            configureAuthentication(jwt, SessionGate(true) { u, s -> auth.sessionAccess(u, s) })
            routing {
                route("/api/v1") {
                    authRoutes(auth, config.auth.accessTokenTtlSeconds, config, AuditService(InMemoryAuditRepository(), CoroutineScope(Dispatchers.Unconfined)),
                        LoginOAuth(google.client(config.auth), OAuthStateService(InMemoryOAuthStateRepository(), null), secondFactorStep = { passkeys.stepAfterSignIn(it) }))
                    passkeyRoutes(passkeys, config)
                    authenticate("auth-jwt") { get("/me") { call.respondText("ok") } }
                }
            }
        }
        block()
    }

    private fun HttpResponse.cookieValue(name: String) =
        headers.getAll(HttpHeaders.SetCookie).orEmpty().firstOrNull { it.startsWith("$name=") }?.substringAfter("$name=")?.substringBefore(";")
    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.code() = json()["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content

    private data class Signed(val access: String, val step: String)

    private suspend fun ApplicationTestBuilder.signIn(): Signed {
        val state = assertNotNull(client.get("/api/v1/auth/google/start").cookieValue(LOGIN_STATE_COOKIE))
        val res = client.post("/api/v1/auth/google") {
            cookie(LOGIN_STATE_COOKIE, state); header(HttpHeaders.Origin, origin); contentType(ContentType.Application.Json)
            setBody("""{"code":"4/0AbCdEf","redirectUri":"${config.app.appUrl}/auth/callback","state":"$state"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val step = res.json()["data"]?.jsonObject?.get("secondFactor")?.jsonPrimitive?.content ?: fail("no secondFactor")
        return Signed(assertNotNull(res.cookieValue(ACCESS_TOKEN_COOKIE)), step)
    }

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, path: String, access: String, body: String? = null) = client.request(path) {
        this.method = method
        cookie(ACCESS_TOKEN_COOKIE, access); header(HttpHeaders.Origin, origin)
        if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
    }

    private suspend fun ApplicationTestBuilder.options(path: String, access: String): String {
        val res = call(HttpMethod.Post, path, access, "{}")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        return res.json()["data"]?.jsonObject?.get("options").toString()
    }

    private fun handleOf(userId: java.util.UUID) = ByteBuffer.allocate(16).putLong(userId.mostSignificantBits).putLong(userId.leastSignificantBits).array()

    @Test
    fun `first sign-in enrols a passkey, gets recovery codes once, and only then opens the app`() = app {
        val s = signIn()
        assertEquals("enrol", s.step)
        assertEquals("AUTH_010", call(HttpMethod.Get, "/api/v1/me", s.access).code())
        val status = call(HttpMethod.Get, "/api/v1/auth/passkey/status", s.access).json()
        assertEquals("enrol", status["data"]?.jsonObject?.get("step")?.jsonPrimitive?.content)

        val created = authenticator.create(options("/api/v1/auth/passkey/register/options", s.access))
        val reg = call(HttpMethod.Post, "/api/v1/auth/passkey/register", s.access, """{"response":$created,"name":"MacBook"}""")
        assertEquals(HttpStatusCode.Created, reg.status, reg.bodyAsText())
        assertEquals(10, reg.json()["data"]?.jsonObject?.get("recoveryCodes")?.jsonArray?.size)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/api/v1/me", s.access).status)
    }

    @Test
    fun `the next sign-in must verify with that passkey`() = app {
        val first = signIn()
        call(HttpMethod.Post, "/api/v1/auth/passkey/register", first.access,
            """{"response":${authenticator.create(options("/api/v1/auth/passkey/register/options", first.access))}}""")

        val next = signIn()
        assertEquals("verify", next.step)
        assertEquals("AUTH_010", call(HttpMethod.Get, "/api/v1/me", next.access).code())
        // Can't sneak in another passkey from the unverified session.
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/api/v1/auth/passkey/register/options", next.access, "{}").status)

        val user = assertNotNull(users.findByEmail("owner@example.com"))
        val assertion = authenticator.get(options("/api/v1/auth/passkey/authenticate/options", next.access), handleOf(user.id))
        val ok = call(HttpMethod.Post, "/api/v1/auth/passkey/authenticate", next.access, """{"response":$assertion}""")
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/api/v1/me", next.access).status)
    }

    @Test
    fun `a forged assertion is a generic 400 that doesn't say why`() = app {
        val first = signIn()
        call(HttpMethod.Post, "/api/v1/auth/passkey/register", first.access,
            """{"response":${authenticator.create(options("/api/v1/auth/passkey/register/options", first.access))}}""")
        val next = signIn()
        val user = assertNotNull(users.findByEmail("owner@example.com"))
        val forged = authenticator.get(options("/api/v1/auth/passkey/authenticate/options", next.access), handleOf(user.id),
            SoftAuthenticator.Tweaks(origin = "https://evil.example"))
        val res = call(HttpMethod.Post, "/api/v1/auth/passkey/authenticate", next.access, """{"response":$forged}""")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("PASSKEY_001", res.code())
        assertFalse("origin" in res.bodyAsText().lowercase(), res.bodyAsText())
        assertEquals("AUTH_010", call(HttpMethod.Get, "/api/v1/me", next.access).code())
    }

    @Test
    fun `a recovery code opens the app instead of a lost passkey`() = app {
        val first = signIn()
        val reg = call(HttpMethod.Post, "/api/v1/auth/passkey/register", first.access,
            """{"response":${authenticator.create(options("/api/v1/auth/passkey/register/options", first.access))}}""")
        val code = reg.json()["data"]?.jsonObject?.get("recoveryCodes")?.jsonArray?.first()?.jsonPrimitive?.content ?: fail("no code")
        val next = signIn()
        val res = call(HttpMethod.Post, "/api/v1/auth/passkey/recover", next.access, """{"code":"$code"}""")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/api/v1/me", next.access).status)
        assertEquals("PASSKEY_005", call(HttpMethod.Post, "/api/v1/auth/passkey/recover", signIn().access, """{"code":"$code"}""").code())
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/api/v1/auth/passkey/recover", signIn().access, """{"code":"${"A".repeat(65)}"}""").status)
    }

    @Test
    fun `too many bad recovery codes sign the session out and clear its cookies`() = app {
        val first = signIn()
        call(HttpMethod.Post, "/api/v1/auth/passkey/register", first.access,
            """{"response":${authenticator.create(options("/api/v1/auth/passkey/register/options", first.access))}}""")
        val next = signIn()
        repeat(4) { call(HttpMethod.Post, "/api/v1/auth/passkey/recover", next.access, """{"code":"AAAA-AAAA-AAAA-AAAA"}""") }
        val last = call(HttpMethod.Post, "/api/v1/auth/passkey/recover", next.access, """{"code":"AAAA-AAAA-AAAA-AAAA"}""")
        assertEquals(HttpStatusCode.Unauthorized, last.status)
        assertEquals("PASSKEY_006", last.code())
        assertTrue(last.headers.getAll(HttpHeaders.SetCookie).orEmpty().any { it.startsWith("$ACCESS_TOKEN_COOKIE=") && "Max-Age=0" in it })
        assertEquals("AUTH_002", call(HttpMethod.Get, "/api/v1/auth/passkey/status", next.access).code())
    }

    @Test
    fun `new recovery codes need a verified session`() = app {
        val first = signIn()
        call(HttpMethod.Post, "/api/v1/auth/passkey/register", first.access,
            """{"response":${authenticator.create(options("/api/v1/auth/passkey/register/options", first.access))}}""")
        val fresh = call(HttpMethod.Post, "/api/v1/auth/passkey/recovery-codes", first.access, "{}")
        assertEquals(HttpStatusCode.OK, fresh.status)
        assertEquals(10, fresh.json()["data"]?.jsonObject?.get("recoveryCodes")?.jsonArray?.size)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/api/v1/auth/passkey/recovery-codes", signIn().access, "{}").status)
    }

    @Test
    fun `the passkey step needs a session, a cross-site post is refused, and an oversized response too`() = app {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/auth/passkey/status").status)
        val s = signIn()
        val cross = client.post("/api/v1/auth/passkey/register/options") { cookie(ACCESS_TOKEN_COOKIE, s.access); header(HttpHeaders.Origin, "https://evil.example") }
        assertEquals(HttpStatusCode.Forbidden, cross.status)
        options("/api/v1/auth/passkey/register/options", s.access)
        val big = call(HttpMethod.Post, "/api/v1/auth/passkey/register", s.access, """{"response":"${"x".repeat(70_000)}"}""")
        assertEquals(HttpStatusCode.BadRequest, big.status)
        // A token without a session (dev-mode style) can't do the passkey step.
        val user = assertNotNull(users.findByEmail("owner@example.com"))
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/api/v1/auth/passkey/status", jwt.generateAccessToken(user)).status)
    }

    @Test
    fun `an expired ceremony says to start again`() = app {
        val s = signIn()
        val res = call(HttpMethod.Post, "/api/v1/auth/passkey/register", s.access, """{"response":{}}""")
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertEquals("PASSKEY_002", res.code())
        assertEquals("PASSKEY_004", call(HttpMethod.Post, "/api/v1/auth/passkey/authenticate/options", s.access, "{}").code())
    }
}
