package dev.adminos.api.domain.identity

import dev.adminos.api.domain.ingestion.connection.InMemoryOAuthStateRepository
import dev.adminos.api.domain.ingestion.connection.OAuthStateService
import io.ktor.client.request.get

import dev.adminos.api.config.AppConfig
import dev.adminos.api.di.testAppModule
import dev.adminos.api.domain.audit.AuditActions
import dev.adminos.api.domain.audit.AuditLog
import dev.adminos.api.domain.audit.AuditRepository
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.InMemoryAuditRepository
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.*

/** AOS-312: /auth routes end to end: Google login, refresh errors, logout audit, session management. */
/** The fake Google user's email (FakeGoogleServer), configured as the owner (AOS-614). */
private const val OWNER = "pragun.b@gmail.com"

class IdentityAuthRoutesCoverageTest {

    private val google = FakeGoogleServer()
    private val baseConfig = AppConfig.load().let { it.copy(auth = it.auth.copy(ownerEmail = OWNER, googleClientId = "test-client-id")) }
    private val users = InMemoryUserRepository()
    private val sessions = InMemorySessionRepository()
    private val audit = RecordingAuditRepository()
    private val ip = uniqueTestIp()
    private val callback = "${baseConfig.app.appUrl}/auth/callback"

    @AfterTest
    fun stop() = google.close()

    private fun routesTest(config: AppConfig = baseConfig, block: suspend ApplicationTestBuilder.(JwtService) -> Unit) = testApplication {
        val jwt = JwtService(config.auth)
        val service = AuthService(users, sessions, jwt, google.client(config.auth), config.auth)
        environment { this.config = MapApplicationConfig() }
        application {
            configureForwardedHeaders(); configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwt)
            routing {
                route("/api/v1") {
                    authRoutes(service, config.auth.accessTokenTtlSeconds, config, AuditService(audit, CoroutineScope(Dispatchers.Unconfined)),
                        LoginOAuth(google.client(config.auth), OAuthStateService(InMemoryOAuthStateRepository(), null)))
                }
            }
        }
        block(jwt)
    }

    private suspend fun ApplicationTestBuilder.postJson(path: String, body: String, setup: HttpRequestBuilder.() -> Unit = {}) =
        client.post(path) {
            header("X-Forwarded-For", ip)
            contentType(ContentType.Application.Json)
            setBody(body)
            setup()
        }

    private suspend fun ApplicationTestBuilder.post(path: String, setup: HttpRequestBuilder.() -> Unit = {}) =
        client.post(path) { header("X-Forwarded-For", ip); setup() }

    private fun loginBody(redirectUri: String = callback, state: String? = null) =
        """{"code":"4/0AbCdEf","redirectUri":"$redirectUri"${state?.let { ",\"state\":\"$it\"" } ?: ""}}"""

    /** A browser sign-in (AOS-618): start (state cookie + Google URL), then post Google's code with that state. */
    private suspend fun ApplicationTestBuilder.login(redirectUri: String = callback): HttpResponse {
        val start = client.get("/api/v1/auth/google/start") { header("X-Forwarded-For", ip) }
        assertEquals(HttpStatusCode.OK, start.status, start.bodyAsText())
        val state = assertNotNull(start.setCookie(LOGIN_STATE_COOKIE)).substringAfter("$LOGIN_STATE_COOKIE=").substringBefore(";")
        return postJson("/api/v1/auth/google", loginBody(redirectUri, state)) { cookie(LOGIN_STATE_COOKIE, state) }
    }

    private fun HttpResponse.setCookie(name: String) = headers.getAll(HttpHeaders.SetCookie).orEmpty().firstOrNull { it.startsWith("$name=") }

    private suspend fun HttpResponse.errorCode() =
        Json.parseToJsonElement(bodyAsText()).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content

    private fun newUser(): User = runBlocking { users.save(User(email = OWNER, googleId = "g-${UUID.randomUUID()}")) }

    private fun newSession(userId: UUID, lastActiveAt: Instant? = null, revokedAt: Instant? = null): Pair<Session, String> = runBlocking {
        val refresh = "refresh-${UUID.randomUUID()}"
        val s = Session(
            userId = userId, tokenHash = JwtService.hashToken(refresh), ipAddress = "203.0.113.7", userAgent = "AdminOS-iOS/1.0",
            expiresAt = Instant.now().plus(30, ChronoUnit.DAYS), lastActiveAt = lastActiveAt, revokedAt = revokedAt
        )
        sessions.save(s)
        s to refresh
    }

    // --- POST /auth/google ---

    @Test
    fun `google login with a foreign redirect uri is rejected before calling google`() = routesTest {
        val res = login("https://evil.example/auth/callback")

        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("AUTH_005", res.errorCode())
        assertTrue(google.tokenRequests.isEmpty())
    }

    @Test
    fun `google login sets both session cookies and returns the user without tokens`() = routesTest {
        val res = login()

        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue("pragun.b@gmail.com" in body)
        assertFalse("ya29" in body || "refreshToken" in body || "accessToken" in body, "tokens must never be in the body")
        assertNotNull(res.setCookie(ACCESS_TOKEN_COOKIE))
        assertNotNull(res.setCookie(REFRESH_TOKEN_COOKIE))
    }

    @Test
    fun `a google account that isn't the owner gets 403 private, no cookies, and the owner is never named (AOS-614)`() = routesTest {
        google.userInfoBody = FakeGoogleServer.userInfo(sub = "stranger-9", email = "stranger@example.com")
        val res = login()

        assertEquals(HttpStatusCode.Forbidden, res.status)
        val body = res.bodyAsText()
        assertTrue("USER_003" in body, body)
        assertFalse(OWNER in body, "the owner's email must never be revealed")
        assertEquals(null, res.setCookie(ACCESS_TOKEN_COOKIE))
        assertEquals(null, res.setCookie(REFRESH_TOKEN_COOKIE))
    }

    @Test
    fun `the refresh cookie is HttpOnly, SameSite=Strict and scoped to the auth path`() = routesTest {
        val refresh = assertNotNull(login().setCookie(REFRESH_TOKEN_COOKIE))

        assertTrue("HttpOnly" in refresh)
        assertTrue("SameSite=Strict" in refresh)
        assertTrue("Path=/api/v1/auth" in refresh)
    }

    @Test
    fun `the refresh cookie from login matches the stored session`() = routesTest {
        val cookie = assertNotNull(login().setCookie(REFRESH_TOKEN_COOKIE))
        val refresh = cookie.substringAfter("$REFRESH_TOKEN_COOKIE=").substringBefore(";")

        val session = assertNotNull(sessions.findByTokenHash(JwtService.hashToken(refresh)))
        assertEquals(ip, session.ipAddress)
    }

    @Test
    fun `google login is audited with the client ip`() = routesTest {
        login()

        val user = assertNotNull(users.findByGoogleId("109876543210987654321"))
        val entry = audit.findByUserId(user.id, 10).single()
        assertEquals(AuditActions.AUTH_LOGIN, entry.action)
        assertEquals(ip, entry.ipAddress)
    }

    @Test
    fun `cookies are Secure when the app is not served from localhost`() = routesTest(baseConfig.copy(app = baseConfig.app.copy(appUrl = "https://adminos.example.in"))) {
        val res = login("https://adminos.example.in/auth/callback")

        assertTrue("Secure" in res.setCookie(ACCESS_TOKEN_COOKIE)!!)
        assertTrue("Secure" in res.setCookie(REFRESH_TOKEN_COOKIE)!!)
    }

    @Test
    fun `cookies are not Secure on the http localhost dev url`() = routesTest {
        val res = login()

        assertFalse("Secure" in res.setCookie(ACCESS_TOKEN_COOKIE)!!)
    }

    @Test
    fun `cookies are Secure for a host that merely starts with localhost`() = routesTest(baseConfig.copy(app = baseConfig.app.copy(appUrl = "http://localhost.example.in"))) {
        val res = login("http://localhost.example.in/auth/callback")

        assertTrue("Secure" in res.setCookie(ACCESS_TOKEN_COOKIE)!!)
    }

    @Test
    fun `google login fails with OAUTH_FAILED when google rejects the code`() = routesTest {
        google.tokenStatus = 400
        google.tokenBody = """{"error":"invalid_grant"}"""

        val res = login()

        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("CONN_002", res.errorCode())
        assertNull(res.setCookie(ACCESS_TOKEN_COOKIE))
    }

    @Test
    fun `google login with an unverified email is a 401 and creates no session`() = routesTest {
        google.userInfoBody = FakeGoogleServer.userInfo(emailVerified = false)

        val res = login()

        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertNull(users.findByGoogleId("109876543210987654321"))
    }

    @Test
    fun `google login with a malformed body is a 400`() = routesTest {
        val res = postJson("/api/v1/auth/google", """{"code":"4/0AbCdEf"}""")

        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    // --- POST /auth/refresh ---

    @Test
    fun `refresh with a blank cookie is a 401`() = routesTest {
        val res = post("/api/v1/auth/refresh") { header(HttpHeaders.Cookie, "$REFRESH_TOKEN_COOKIE=") }

        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("AUTH_002", res.errorCode())
    }

    @Test
    fun `refresh with an unknown token is TOKEN_INVALID and clears both cookies`() = routesTest {
        val res = post("/api/v1/auth/refresh") { cookie(REFRESH_TOKEN_COOKIE, "never-issued") }

        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("AUTH_002", res.errorCode())
        assertTrue(res.setCookie(ACCESS_TOKEN_COOKIE)!!.contains("Max-Age=0"))
        assertTrue(res.setCookie(REFRESH_TOKEN_COOKIE)!!.contains("Max-Age=0"))
    }

    @Test
    fun `refresh with a revoked session is SESSION_REVOKED`() = routesTest {
        val (_, refresh) = newSession(newUser().id, revokedAt = Instant.now().minusSeconds(60))

        val res = post("/api/v1/auth/refresh") { cookie(REFRESH_TOKEN_COOKIE, refresh) }

        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("AUTH_004", res.errorCode())
    }

    @Test
    fun `refresh for a deleted user is USER_NOT_FOUND and clears cookies`() = routesTest {
        val (_, refresh) = newSession(UUID.randomUUID())

        val res = post("/api/v1/auth/refresh") { cookie(REFRESH_TOKEN_COOKIE, refresh) }

        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("USER_001", res.errorCode())
        assertTrue(res.setCookie(REFRESH_TOKEN_COOKIE)!!.contains("Max-Age=0"))
    }

    // --- POST /auth/logout ---

    @Test
    fun `logout without a refresh cookie still clears cookies and audits nothing`() = routesTest {
        val res = post("/api/v1/auth/logout")

        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.setCookie(REFRESH_TOKEN_COOKIE)!!.contains("Max-Age=0"))
        assertTrue(audit.saved.isEmpty())
    }

    @Test
    fun `logout with an unknown refresh token audits nothing`() = routesTest {
        val res = post("/api/v1/auth/logout") { cookie(REFRESH_TOKEN_COOKIE, "never-issued") }

        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(audit.saved.isEmpty())
    }

    @Test
    fun `logout is audited for the session's user`() = routesTest {
        val user = newUser()
        val (_, refresh) = newSession(user.id)

        post("/api/v1/auth/logout") { cookie(REFRESH_TOKEN_COOKIE, refresh) }

        val entry = audit.findByUserId(user.id, 10).single()
        assertEquals(AuditActions.AUTH_LOGOUT, entry.action)
        assertEquals(ip, entry.ipAddress)
    }

    // --- GET/DELETE /auth/sessions ---

    @Test
    fun `listing sessions needs a login`() = routesTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/auth/sessions") { header("X-Forwarded-For", ip) }.status)
    }

    @Test
    fun `listing sessions returns only the caller's active sessions`() = routesTest { jwt ->
        val me = newUser()
        val (active, _) = newSession(me.id, lastActiveAt = Instant.parse("2026-10-15T08:30:00Z"))
        val (fresh, _) = newSession(me.id)
        newSession(me.id, revokedAt = Instant.now().minusSeconds(5))
        newSession(newUser().id)

        val res = client.get("/api/v1/auth/sessions") { header("X-Forwarded-For", ip); bearerAuth(jwt.generateAccessToken(me)) }

        assertEquals(HttpStatusCode.OK, res.status)
        val data = Json.parseToJsonElement(res.bodyAsText()).jsonObject["data"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf(active.id.toString(), fresh.id.toString()), data.map { it["id"]!!.jsonPrimitive.content }.toSet())
        val a = data.single { it["id"]!!.jsonPrimitive.content == active.id.toString() }
        assertEquals("2026-10-15T08:30:00Z", a["lastActiveAt"]!!.jsonPrimitive.content)
        assertEquals("AdminOS-iOS/1.0", a["userAgent"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, data.single { it["id"]!!.jsonPrimitive.content == fresh.id.toString() }["lastActiveAt"])
    }

    @Test
    fun `revoking one of my sessions ends it and is audited`() = routesTest { jwt ->
        val me = newUser()
        val (session, refresh) = newSession(me.id)

        val res = client.delete("/api/v1/auth/sessions/${session.id}") { header("X-Forwarded-For", ip); bearerAuth(jwt.generateAccessToken(me)) }

        assertEquals(HttpStatusCode.OK, res.status)
        assertNull(sessions.findActiveByUserId(me.id).firstOrNull { it.id == session.id })
        val entry = audit.findByEntity("session", session.id).single()
        assertEquals(AuditActions.AUTH_SESSION_REVOKE, entry.action)
        assertEquals(HttpStatusCode.Unauthorized, post("/api/v1/auth/refresh") { cookie(REFRESH_TOKEN_COOKIE, refresh) }.status)
    }

    @Test
    fun `another user's session cannot be revoked`() = routesTest { jwt ->
        val victim = newUser()
        val (session, _) = newSession(victim.id)

        val res = client.delete("/api/v1/auth/sessions/${session.id}") { header("X-Forwarded-For", ip); bearerAuth(jwt.generateAccessToken(newUser())) }

        assertEquals(listOf(session.id), sessions.findActiveByUserId(victim.id).map { it.id })
        // AOS-610: "not found" (it isn't yours), and no revoke is audited.
        assertEquals(HttpStatusCode.NotFound, res.status)
        assertEquals("AUTH_006", res.errorCode())
        assertTrue(audit.saved.none { it.action == AuditActions.AUTH_SESSION_REVOKE })
    }

    @Test
    fun `a deactivated user's refresh is refused, the cookies are cleared and the session revoked (AOS-610)`() = routesTest {
        val user = runBlocking { users.save(User(email = "gone-${UUID.randomUUID()}@example.com", googleId = "g-${UUID.randomUUID()}", name = "Gone", isActive = false)) }
        val (session, refresh) = newSession(user.id)
        val res = client.post("/api/v1/auth/refresh") { header("X-Forwarded-For", ip); cookie("refresh_token", refresh) }
        assertEquals(HttpStatusCode.Forbidden, res.status)
        assertEquals("USER_002", res.errorCode())
        assertTrue(res.headers.getAll(HttpHeaders.SetCookie)!!.any { it.startsWith("refresh_token=;") })
        assertTrue(sessions.findActiveByUserId(user.id).none { it.id == session.id })
    }

    @Test
    fun `revoking a session with a malformed id is a 400`() = routesTest { jwt ->
        val res = client.delete("/api/v1/auth/sessions/not-a-uuid") { header("X-Forwarded-For", ip); bearerAuth(jwt.generateAccessToken(newUser())) }

        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    // --- Rate limit ---

    @Test
    fun `the eleventh auth request in a minute from one ip is rate limited`() = routesTest {
        repeat(10) { assertEquals(HttpStatusCode.OK, post("/api/v1/auth/logout").status) }

        val res = post("/api/v1/auth/logout")

        assertEquals(HttpStatusCode.TooManyRequests, res.status)
        assertEquals("RATE_001", res.errorCode())
    }

    @Test
    fun `a rate-limited logout does not revoke the session`() = routesTest {
        val user = newUser()
        val (session, refresh) = newSession(user.id)
        repeat(10) { post("/api/v1/auth/logout") }

        post("/api/v1/auth/logout") { cookie(REFRESH_TOKEN_COOKIE, refresh) }

        assertEquals(listOf(session.id), sessions.findActiveByUserId(user.id).map { it.id })
    }

    // --- Module wiring ---

    @Test
    fun `the identity module mounts auth and device-token routes under api v1`() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(Koin) { modules(testAppModule, module { single { DeviceTokenService(InMemoryDeviceTokenRepository(), get()) }; single { OAuthStateService(InMemoryOAuthStateRepository(), null) }
                single { PasskeyService(InMemoryPasskeyRepository(), InMemoryRecoveryCodeRepository(), get(), get(), dev.adminos.api.infrastructure.webauthn.YubicoWebAuthn(baseConfig.app.appUrl)) } }) }
            configureForwardedHeaders(); configureSerialization(); configureRequestId(); configureErrorHandling()
            configureAuthentication(JwtService(baseConfig.auth))
            identityModule(baseConfig)
        }

        assertEquals(HttpStatusCode.OK, client.post("/api/v1/auth/logout") { header("X-Forwarded-For", ip) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/device-tokens").status)
    }
}

/** The in-memory audit fake, plus a list of everything saved (logout entries have no entity id to query by). */
internal class RecordingAuditRepository(private val inner: InMemoryAuditRepository = InMemoryAuditRepository()) : AuditRepository by inner {
    val saved = java.util.concurrent.CopyOnWriteArrayList<AuditLog>()
    override suspend fun save(auditLog: AuditLog) { saved += auditLog; inner.save(auditLog) }
}
