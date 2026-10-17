package dev.adminos.api.domain.identity

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.ingestion.connection.InMemoryOAuthStateRepository
import dev.adminos.api.domain.ingestion.connection.OAuthPurpose
import dev.adminos.api.domain.ingestion.connection.OAuthStateClaim
import dev.adminos.api.domain.ingestion.connection.OAuthStateService
import dev.adminos.api.domain.scheduling.TestClock
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.URLDecoder
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/**
 * Sign-in hardening (AOS-618): a server-side state + PKCE bound to the browser (AUD-06), identity-only scopes
 * (AUD-19), and lockout with backoff for refused attempts (AUD-16).
 */
class SignInHardeningTest {

    private val google = FakeGoogleServer()
    private val config = AppConfig.load().let { it.copy(auth = it.auth.copy(ownerEmail = "pragun.b@gmail.com", googleClientId = "test-client-id")) }
    private val users = InMemoryUserRepository()
    private val stateRepo = InMemoryOAuthStateRepository()
    private val states = OAuthStateService(stateRepo, null)
    private val callback = "${config.app.appUrl}/auth/callback"

    @AfterTest
    fun stop() = google.close()

    private fun signInTest(guard: SignInGuard = SignInGuard(), cfg: AppConfig = config, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val jwt = JwtService(cfg.auth)
        val service = AuthService(users, InMemorySessionRepository(), jwt, google.client(cfg.auth), cfg.auth)
        environment { this.config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwt)
            routing {
                route("/api/v1") {
                    authRoutes(service, cfg.auth.accessTokenTtlSeconds, cfg, AuditService(dev.adminos.api.domain.audit.InMemoryAuditRepository(), CoroutineScope(Dispatchers.Unconfined)),
                        LoginOAuth(google.client(cfg.auth), states, guard))
                }
            }
        }
        block()
    }

    private fun HttpResponse.setCookie(name: String) = headers.getAll(HttpHeaders.SetCookie).orEmpty().firstOrNull { it.startsWith("$name=") }
    private fun query(url: String) = URI(url).rawQuery.split('&').associate { p ->
        val (k, v) = p.split('=', limit = 2); URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
    }

    private data class Started(val state: String, val url: String, val cookie: String)

    private suspend fun ApplicationTestBuilder.start(): Started {
        val res = client.get("/api/v1/auth/google/start")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val cookie = assertNotNull(res.setCookie(LOGIN_STATE_COOKIE))
        val url = Regex("\"url\":\"([^\"]+)\"").find(res.bodyAsText())?.groupValues?.get(1)?.replace("\\u0026", "&") ?: fail("no url")
        return Started(cookie.substringAfter("$LOGIN_STATE_COOKIE=").substringBefore(";"), url, cookie)
    }

    private suspend fun ApplicationTestBuilder.finish(state: String?, cookie: String?, redirect: String = callback) =
        client.post("/api/v1/auth/google") {
            if (cookie != null) cookie(LOGIN_STATE_COOKIE, cookie)
            contentType(ContentType.Application.Json)
            setBody("""{"code":"4/0AbCdEf","redirectUri":"$redirect"${state?.let { ",\"state\":\"$it\"" } ?: ""}}""")
        }

    private suspend fun HttpResponse.code() = Regex("\"code\":\"([A-Z_0-9]+)\"").find(bodyAsText())?.groupValues?.get(1)

    @Test
    fun `start gives Google's URL with a state, a PKCE challenge and identity scopes only`() = signInTest {
        val s = start()
        val q = query(s.url)
        assertTrue(s.url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        assertEquals(s.state, q["state"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals(43, q["code_challenge"]?.length)
        assertEquals("openid email profile", q["scope"])
        assertEquals(callback, q["redirect_uri"])
        assertNull(q["access_type"], "no offline access at sign-in (AUD-19)")
        assertNotEquals("consent", q["prompt"])
        assertTrue("HttpOnly" in s.cookie && "SameSite=Strict" in s.cookie && "Path=/api/v1/auth" in s.cookie && "Max-Age=600" in s.cookie)
    }

    @Test
    fun `the code is exchanged with the state's PKCE verifier and the state cookie is cleared`() = signInTest {
        val s = start()
        val res = finish(s.state, s.state)
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val verifier = assertNotNull(google.lastTokenForm()["code_verifier"])
        assertEquals(query(s.url)["code_challenge"], OAuthStateService.codeChallenge(verifier))
        assertTrue(assertNotNull(res.setCookie(LOGIN_STATE_COOKIE)).contains("Max-Age=0"))
    }

    @Test
    fun `no state, or a state without its cookie, is refused before Google is called`() = signInTest {
        val s = start()
        assertEquals("AUTH_007", finish(null, s.state).code())
        assertEquals("AUTH_007", finish(s.state, null).code())
        assertTrue(google.tokenRequests.isEmpty())
    }

    @Test
    fun `a state planted from another browser is refused (cookie and body must match)`() = signInTest {
        val victim = start()
        val attacker = start()
        val res = finish(attacker.state, victim.state)
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("AUTH_007", res.code())
        assertTrue(google.tokenRequests.isEmpty())
    }

    @Test
    fun `a state works once`() = signInTest {
        val s = start()
        assertEquals(HttpStatusCode.OK, finish(s.state, s.state).status)
        val replay = finish(s.state, s.state)
        assertEquals(HttpStatusCode.BadRequest, replay.status)
        assertEquals("AUTH_007", replay.code())
    }

    @Test
    fun `a state issued for connecting Gmail can't be used to sign in`() = signInTest {
        val gmail = runBlocking { states.issue(OAuthStateClaim(UUID.randomUUID(), OAuthPurpose.GMAIL_CONNECT, callback)) }
        assertEquals("AUTH_007", finish(gmail.state, gmail.state).code())
    }

    @Test
    fun `start without a Google client is 503 and sets no cookie`() =
        signInTest(cfg = config.copy(auth = config.auth.copy(googleClientId = ""))) {
            val res = client.get("/api/v1/auth/google/start")
            assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
            assertEquals("AUTH_008", res.code())
            assertNull(res.setCookie(LOGIN_STATE_COOKIE))
        }

    @Test
    fun `after five refused sign-ins the client waits, with Retry-After, and even start is refused`() = signInTest {
        repeat(5) { assertEquals("AUTH_007", finish("nope-$it", "nope-$it").code()) }
        val locked = finish("nope", "nope")
        assertEquals(HttpStatusCode.BadRequest, locked.status) // the sixth refusal is what sets the lock
        val next = client.get("/api/v1/auth/google/start")
        assertEquals(HttpStatusCode.TooManyRequests, next.status)
        assertEquals("RATE_002", next.code())
        assertTrue((next.headers[HttpHeaders.RetryAfter]?.toLong() ?: 0) in 1..60)
    }

    @Test
    fun `a stranger's Google account counts as a refused sign-in`() {
        val guard = SignInGuard()
        signInTest(guard) {
            google.userInfoBody = FakeGoogleServer.userInfo(sub = "stranger-1", email = "stranger@example.com")
            val s = start()
            assertEquals("USER_003", finish(s.state, s.state).code())
        }
        assertEquals(1, guard.trackedClients)
    }

    @Test
    fun `the owner signing in clears earlier refusals`() {
        val guard = SignInGuard()
        signInTest(guard) {
            repeat(3) { finish("nope-$it", "nope-$it") }
            val s = start()
            assertEquals(HttpStatusCode.OK, finish(s.state, s.state).status)
        }
        assertEquals(0, guard.trackedClients)
    }

    // ── SignInGuard ──

    @Test
    fun `the guard allows the free attempts, then doubles the lock up to its cap`() {
        val clock = TestClock(Instant.parse("2026-10-18T09:00:00Z"))
        val guard = SignInGuard(clock, freeFailures = 2, baseLock = Duration.ofMinutes(1), maxLock = Duration.ofMinutes(4))
        assertNull(guard.refused("c"))
        assertNull(guard.refused("c"))
        assertNull(guard.lockedFor("c"))
        assertEquals(Duration.ofMinutes(1), guard.refused("c"))
        assertEquals(Duration.ofMinutes(2), guard.refused("c"))
        assertEquals(Duration.ofMinutes(4), guard.refused("c"))
        assertEquals(Duration.ofMinutes(4), guard.refused("c"), "capped")
        assertEquals(Duration.ofMinutes(4), guard.lockedFor("c"))
        assertNull(guard.lockedFor("someone-else"))
    }

    @Test
    fun `a lock runs out, and refusals older than the window are forgotten`() {
        val clock = TestClock(Instant.parse("2026-10-18T09:00:00Z"))
        val guard = SignInGuard(clock, freeFailures = 1, window = Duration.ofMinutes(15))
        guard.refused("c"); guard.refused("c")
        assertNotNull(guard.lockedFor("c"))
        clock.advance(Duration.ofMinutes(2))
        assertNull(guard.lockedFor("c"))
        clock.advance(Duration.ofMinutes(20))
        assertNull(guard.refused("c"), "a fresh window starts with free attempts again")
    }

    @Test
    fun `the guard tracks a bounded number of clients`() {
        val guard = SignInGuard(maxClients = 100)
        repeat(1000) { guard.refused("client-$it") }
        assertTrue(guard.trackedClients <= 100, "tracked ${guard.trackedClients}")
    }

    @Test
    fun `the guard refuses nonsense settings`() {
        assertFailsWith<IllegalArgumentException> { SignInGuard(freeFailures = 0) }
        assertFailsWith<IllegalArgumentException> { SignInGuard(baseLock = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { SignInGuard(baseLock = Duration.ofHours(2), maxLock = Duration.ofHours(1)) }
    }

    @Test
    fun `secrets are compared in constant time and exactly`() {
        assertTrue(constantTimeEquals("abc", "abc"))
        assertFalse(constantTimeEquals("abc", "abd"))
        assertFalse(constantTimeEquals("abc", "abcd"))
    }

    @Test
    fun `a sign-in state needs no user and every other purpose needs one`() {
        assertFailsWith<IllegalArgumentException> { OAuthStateClaim(null, OAuthPurpose.GMAIL_CONNECT, callback) }
        assertFailsWith<IllegalArgumentException> { OAuthStateClaim(UUID.randomUUID(), OAuthPurpose.LOGIN, callback) }
        assertEquals(null, OAuthStateClaim.login(callback).userId)
    }
}
