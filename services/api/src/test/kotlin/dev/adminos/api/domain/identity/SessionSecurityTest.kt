package dev.adminos.api.domain.identity

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.InMemoryAuditRepository
import dev.adminos.api.domain.ingestion.connection.InMemoryOAuthStateRepository
import dev.adminos.api.domain.ingestion.connection.OAuthStateService
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.response.respondText
import io.ktor.server.auth.authenticate
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/**
 * Sessions you can trust and cut off (AOS-620, AOS-121): refresh-token rotation with reuse detection, access tokens
 * that die with their session, sign out everywhere, and alerts on new and refused sign-ins.
 */
class SessionSecurityTest {

    private val google = FakeGoogleServer()
    private val config = AppConfig.load().let { it.copy(auth = it.auth.copy(ownerEmail = "owner@example.com", googleClientId = "test-client-id")) }
    private val users = InMemoryUserRepository()
    private val sessions = InMemorySessionRepository()
    private val jwt = JwtService(config.auth)
    private val sent = CopyOnWriteArrayList<Pair<UUID, SecurityAlert>>()
    private val alerts = SignInAlerts({ user, alert -> sent += user to alert })
    private val auth = AuthService(users, sessions, jwt, google.client(config.auth), config.auth, alerts = alerts)
    private val origin = config.app.appUrl
    private val mac = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_0) AppleWebKit/605.1.15 Version/18.0 Safari/605.1.15"
    private val iphone = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile/15E148 Safari/604.1"

    @AfterTest
    fun stop() = google.close()

    private fun app(requireSession: Boolean = true, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { this.config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureHTTP(origin)
            configureAuthentication(jwt, SessionGate(requireSession) { u, s -> auth.sessionAccess(u, s) })
            routing {
                route("/api/v1") {
                    authRoutes(auth, config.auth.accessTokenTtlSeconds, config, AuditService(InMemoryAuditRepository(), CoroutineScope(Dispatchers.Unconfined)),
                        LoginOAuth(google.client(config.auth), OAuthStateService(InMemoryOAuthStateRepository(), null)))
                    authenticate("auth-jwt") { get("/me") { call.respondText(call.userPrincipal.userId.toString()) } }
                }
            }
        }
        block()
    }

    private fun HttpResponse.cookieValue(name: String) =
        headers.getAll(HttpHeaders.SetCookie).orEmpty().firstOrNull { it.startsWith("$name=") }?.substringAfter("$name=")?.substringBefore(";")

    private data class Signed(val access: String, val refresh: String)

    /**
     * A real sign-in through the routes (start → Google → callback), from [userAgent]. With [passkey] the session is then
     * marked as having passed the second factor (what the passkey step does, AOS-617), so it can use the app.
     */
    private suspend fun ApplicationTestBuilder.signIn(userAgent: String = mac, passkey: Boolean = true): Signed {
        val start = client.get("/api/v1/auth/google/start")
        val state = assertNotNull(start.cookieValue(LOGIN_STATE_COOKIE))
        val res = client.post("/api/v1/auth/google") {
            cookie(LOGIN_STATE_COOKIE, state); header(HttpHeaders.UserAgent, userAgent); header(HttpHeaders.Origin, origin)
            contentType(ContentType.Application.Json)
            setBody("""{"code":"4/0AbCdEf","redirectUri":"${config.app.appUrl}/auth/callback","state":"$state"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val signed = Signed(assertNotNull(res.cookieValue(ACCESS_TOKEN_COOKIE)), assertNotNull(res.cookieValue(REFRESH_TOKEN_COOKIE)))
        if (passkey) sessions.markSecondFactor(assertNotNull(jwt.verifyAccessToken(signed.access)?.sessionId), Instant.now())
        return signed
    }

    @Test
    fun `a session that hasn't passed its passkey can't use the app, and is told why`() = app {
        val s = signIn(passkey = false)
        val res = me(s.access)
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("AUTH_010", res.code())
        // Refreshing doesn't get around it: the new token belongs to the same pending session.
        val refreshed = assertNotNull(refresh(s.refresh).cookieValue(ACCESS_TOKEN_COOKIE))
        assertEquals("AUTH_010", me(refreshed).code())
    }

    private suspend fun ApplicationTestBuilder.refresh(token: String) = client.post("/api/v1/auth/refresh") {
        cookie(REFRESH_TOKEN_COOKIE, token); header(HttpHeaders.Origin, origin)
    }

    private suspend fun ApplicationTestBuilder.me(access: String) = client.get("/api/v1/me") { cookie(ACCESS_TOKEN_COOKIE, access) }

    private suspend fun HttpResponse.code() = Regex("\"code\":\"([A-Z_0-9]+)\"").find(bodyAsText())?.groupValues?.get(1)

    // ── Rotation and reuse detection ──

    @Test
    fun `every refresh rotates the refresh token, and the new access token names the session`() = app {
        val s = signIn()
        val r = refresh(s.refresh)
        assertEquals(HttpStatusCode.OK, r.status)
        val next = assertNotNull(r.cookieValue(REFRESH_TOKEN_COOKIE))
        assertNotEquals(s.refresh, next)
        val access = assertNotNull(r.cookieValue(ACCESS_TOKEN_COOKIE))
        assertNotNull(jwt.verifyAccessToken(access)?.sessionId)
        assertEquals(HttpStatusCode.OK, refresh(next).status, "the new one works")
    }

    @Test
    fun `a refresh token reused after the grace period ends the session and alerts the owner`() = app {
        val s = signIn()
        val session = assertNotNull(sessions.findByTokenHash(JwtService.hashToken(s.refresh)))
        // The owner refreshed long ago; the attacker now replays the old cookie.
        assertTrue(sessions.rotate(session.id, session.tokenHash, JwtService.hashToken(jwt.newRefreshToken()), Instant.now().minusSeconds(120)))
        sent.clear()
        val replay = refresh(s.refresh)
        assertEquals(HttpStatusCode.Unauthorized, replay.status)
        assertEquals("AUTH_009", replay.code())
        assertNull(sessions.findById(session.id)?.takeIf { it.isActive }, "the whole session is revoked")
        assertEquals(HttpStatusCode.Unauthorized, me(s.access).status, "its access token dies with it")
        val alert = assertIs<SecurityAlert.SessionKeyReused>(sent.single().second)
        assertEquals("Safari on Mac", alert.device)
    }

    @Test
    fun `two tabs refreshing at once both stay signed in`() = app {
        val s = signIn()
        assertEquals(HttpStatusCode.OK, refresh(s.refresh).status)
        val second = refresh(s.refresh) // the slower tab, within the grace period
        assertEquals(HttpStatusCode.OK, second.status, second.bodyAsText())
        assertNotNull(second.cookieValue(ACCESS_TOKEN_COOKIE))
        assertNull(second.cookieValue(REFRESH_TOKEN_COOKIE), "nothing rotates twice")
    }

    @Test
    fun `losing a rotation race gives an access token but no new refresh token`() = runBlocking<Unit> {
        val racing = object : SessionRepository by sessions {
            override suspend fun rotate(sessionId: UUID, oldHash: String, newHash: String, at: Instant) = false
        }
        val user = users.save(User(email = "owner@example.com", googleId = "g-1"))
        val token = jwt.newRefreshToken()
        racing.save(Session(userId = user.id, tokenHash = JwtService.hashToken(token), expiresAt = Instant.now().plus(1, ChronoUnit.DAYS)))
        val result = AuthService(users, racing, jwt, google.client(config.auth), config.auth).refreshToken(token)
        assertNull(result.refreshToken)
        assertNotNull(jwt.verifyAccessToken(result.accessToken))
    }

    @Test
    fun `an unknown refresh token is just not found`() = app {
        assertEquals("AUTH_002", refresh(jwt.newRefreshToken()).code())
    }

    @Test
    fun `a rotated token of a revoked session is revoked, not reuse`() = runBlocking<Unit> {
        val user = users.save(User(email = "owner@example.com", googleId = "g-2"))
        val token = jwt.newRefreshToken()
        val s = sessions.save(Session(userId = user.id, tokenHash = JwtService.hashToken(token), expiresAt = Instant.now().plus(1, ChronoUnit.DAYS)))
        sessions.rotate(s.id, s.tokenHash, JwtService.hashToken(jwt.newRefreshToken()), Instant.now().minusSeconds(600))
        sessions.revoke(s.id)
        assertFailsWith<SessionRevokedException> { auth.refreshToken(token) }
        assertTrue(sent.none { it.second is SecurityAlert.SessionKeyReused })
    }

    // ── Access tokens die with their session (AUD-04) ──

    @Test
    fun `logout ends the access token at once, not when it expires`() = app {
        val s = signIn()
        assertEquals(HttpStatusCode.OK, me(s.access).status)
        client.post("/api/v1/auth/logout") { cookie(REFRESH_TOKEN_COOKIE, s.refresh); header(HttpHeaders.Origin, origin) }
        assertEquals(HttpStatusCode.Unauthorized, me(s.access).status)
    }

    @Test
    fun `with real data a token without a session is refused, even if correctly signed`() = app(requireSession = true) {
        val user = users.save(User(email = "owner@example.com", googleId = "g-3"))
        assertEquals(HttpStatusCode.Unauthorized, me(jwt.generateAccessToken(user)).status)
        assertEquals(HttpStatusCode.Unauthorized, me(jwt.generateAccessToken(user, UUID.randomUUID())).status, "an unknown session")
    }

    @Test
    fun `a token naming someone else's session is refused`() = app {
        val s = signIn()
        val sid = assertNotNull(jwt.verifyAccessToken(s.access)?.sessionId)
        val stranger = User(email = "x@example.com", googleId = "g-x")
        assertEquals(HttpStatusCode.Unauthorized, me(jwt.generateAccessToken(stranger, sid)).status)
    }

    @Test
    fun `in dev mode a token without a session still works (tokens minted for tests)`() = app(requireSession = false) {
        val user = users.save(User(email = "owner@example.com", googleId = "g-4"))
        assertEquals(HttpStatusCode.OK, me(jwt.generateAccessToken(user)).status)
    }

    // ── Sessions page API ──

    @Test
    fun `the sessions list marks this device and names devices coarsely`() = app {
        val laptop = signIn(mac)
        signIn(iphone)
        val res = client.get("/api/v1/auth/sessions") { cookie(ACCESS_TOKEN_COOKIE, laptop.access) }
        val body = res.bodyAsText()
        assertEquals(1, Regex("\"current\":true").findAll(body).count())
        assertTrue("\"device\":\"Safari on Mac\"" in body && "\"device\":\"Safari on iPhone\"" in body, body)
    }

    @Test
    fun `sign out everywhere ends every session, this one too, and clears the cookies`() = app {
        val a = signIn(mac)
        val b = signIn(iphone)
        val res = client.post("/api/v1/auth/sessions/revoke-all") { cookie(ACCESS_TOKEN_COOKIE, a.access); header(HttpHeaders.Origin, origin) }
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue("\"revoked\":2" in res.bodyAsText() && "\"signedOut\":true" in res.bodyAsText())
        assertTrue(res.headers.getAll(HttpHeaders.SetCookie).orEmpty().any { it.startsWith("$ACCESS_TOKEN_COOKIE=;") || it.startsWith("$ACCESS_TOKEN_COOKIE=\"\"") || "Max-Age=0" in it })
        assertEquals(HttpStatusCode.Unauthorized, me(a.access).status)
        assertEquals(HttpStatusCode.Unauthorized, me(b.access).status)
        assertEquals(HttpStatusCode.Unauthorized, refresh(b.refresh).status)
    }

    @Test
    fun `sign out everywhere else keeps this device`() = app {
        val a = signIn(mac)
        val b = signIn(iphone)
        val res = client.post("/api/v1/auth/sessions/revoke-all?keepCurrent=true") { cookie(ACCESS_TOKEN_COOKIE, a.access); header(HttpHeaders.Origin, origin) }
        assertTrue("\"revoked\":1" in res.bodyAsText() && "\"signedOut\":false" in res.bodyAsText(), res.bodyAsText())
        assertEquals(HttpStatusCode.OK, me(a.access).status)
        assertEquals(HttpStatusCode.Unauthorized, me(b.access).status)
    }

    @Test
    fun `revoking this device's own session clears its cookies`() = app {
        val a = signIn(mac)
        val sid = assertNotNull(jwt.verifyAccessToken(a.access)?.sessionId)
        val res = client.delete("/api/v1/auth/sessions/$sid") { cookie(ACCESS_TOKEN_COOKIE, a.access); header(HttpHeaders.Origin, origin) }
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.headers.getAll(HttpHeaders.SetCookie).orEmpty().any { it.startsWith("$REFRESH_TOKEN_COOKIE=") && "Max-Age=0" in it })
    }

    // ── Alerts ──

    @Test
    fun `the first sign-in and each new device alert the owner, a known one doesn't`() = app {
        signIn(mac)
        signIn(mac)
        signIn(iphone)
        val devices = sent.map { (it.second as SecurityAlert.NewSignIn).device }
        assertEquals(listOf("Safari on Mac", "Safari on iPhone"), devices)
        assertTrue(sent.all { "Settings → Security" in it.second.body })
    }

    @Test
    fun `a stranger's refused sign-in alerts the owner with only a masked account`() = app {
        signIn(mac) // the owner exists and is pinned
        sent.clear()
        google.userInfoBody = FakeGoogleServer.userInfo(sub = "stranger-1", email = "call.18005550100@scam-help.example")
        val start = client.get("/api/v1/auth/google/start")
        val state = assertNotNull(start.cookieValue(LOGIN_STATE_COOKIE))
        val res = client.post("/api/v1/auth/google") {
            cookie(LOGIN_STATE_COOKIE, state); header(HttpHeaders.Origin, origin); contentType(ContentType.Application.Json)
            setBody("""{"code":"4/0AbCdEf","redirectUri":"${config.app.appUrl}/auth/callback","state":"$state"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, res.status)
        val alert = assertIs<SecurityAlert.RefusedSignIn>(sent.single().second)
        assertEquals("c•••@s•••.example", alert.maskedAccount)
        assertFalse("1800" in alert.body || "scam" in alert.body, alert.body)
    }

    @Test
    fun `refused sign-ins are batched so a stranger can't flood the owner's phone`() = runBlocking<Unit> {
        val clock = dev.adminos.api.domain.scheduling.TestClock(Instant.parse("2026-10-18T09:00:00Z"))
        val got = CopyOnWriteArrayList<SecurityAlert>()
        val a = SignInAlerts({ _, alert -> got += alert }, clock)
        val owner = UUID.randomUUID()
        repeat(20) { a.refused(owner, "x@example.com") }
        assertEquals(1, got.size)
        clock.advance(java.time.Duration.ofMinutes(16))
        a.refused(owner, "x@example.com")
        val second = assertIs<SecurityAlert.RefusedSignIn>(got.last())
        assertEquals(20, second.attempts, "the 19 held back plus this one")
        assertTrue("20 sign-ins were refused since" in second.body)
    }

    @Test
    fun `no owner yet means nobody to alert, and a failing channel never breaks sign-in`() = runBlocking<Unit> {
        val got = CopyOnWriteArrayList<SecurityAlert>()
        SignInAlerts({ _, alert -> got += alert }).refused(null, "x@example.com")
        assertTrue(got.isEmpty())
        SignInAlerts({ _, _ -> error("push service down") }).newSignIn(UUID.randomUUID(), "Chrome on Mac") // no throw
        SignInAlerts(null).newSignIn(UUID.randomUUID(), "Chrome on Mac") // no notifier: logged, no throw
    }

    @Test
    fun `masked accounts keep only letters and digits`() {
        assertEquals("o•••@e•••.com", SignInAlerts.maskEmail("Owner@Example.com"))
        assertEquals("c•••@s•••.example", SignInAlerts.maskEmail("call+18005550100@scam-help.example"))
        assertNull(SignInAlerts.maskEmail("<script>@x"))
        assertNull(SignInAlerts.maskEmail("no-at-sign"))
        assertNull(SignInAlerts.maskEmail(null))
        assertNull(SignInAlerts.maskEmail("@example.com"))
    }

    @Test
    fun `device labels come from a fixed vocabulary, never the raw user agent`() {
        assertEquals("Chrome on Windows", DeviceLabel.of("Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/140.0 Safari/537.36"))
        assertEquals("Edge on Windows", DeviceLabel.of("Mozilla/5.0 (Windows NT 10.0) Chrome/140.0 Safari/537.36 Edg/140.0"))
        assertEquals("Firefox on Linux", DeviceLabel.of("Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0"))
        assertEquals("Chrome on Android", DeviceLabel.of("Mozilla/5.0 (Linux; Android 15) Chrome/140.0 Mobile Safari/537.36"))
        assertEquals("Unknown device", DeviceLabel.of("<img src=x onerror=alert(1)> call 1800"))
        assertEquals("Unknown device", DeviceLabel.of(null))
    }
}
