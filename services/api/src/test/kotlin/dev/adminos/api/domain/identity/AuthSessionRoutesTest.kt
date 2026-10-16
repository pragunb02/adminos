package dev.adminos.api.domain.identity

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.InMemoryAuditRepository
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.*

/** AOS-109: cookie-based refresh and per-device logout. */
class AuthSessionRoutesTest {

    private val config = AppConfig.load()
    private val jwtService = JwtService(config.auth)
    private val users = InMemoryUserRepository()
    private val sessions = InMemorySessionRepository()
    private val authService = AuthService(users, sessions, jwtService, GoogleOAuthClient(config.auth), config.auth)
    private val user = User(id = UUID.randomUUID(), email = "me@example.com", googleId = "g-1", role = UserRole.OWNER)

    /** A logged-in device: a stored session plus its refresh token. */
    private fun newSession(expiresAt: Instant = Instant.now().plus(30, ChronoUnit.DAYS)): Pair<Session, String> = runBlocking {
        users.save(user)
        val refresh = "refresh-${UUID.randomUUID()}"
        val s = Session(userId = user.id, tokenHash = JwtService.hashToken(refresh), expiresAt = expiresAt)
        sessions.save(s)
        s to refresh
    }

    private fun sessionTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            routing { route("/api/v1") { authRoutes(authService, config.auth.accessTokenTtlSeconds, config, AuditService(InMemoryAuditRepository(), CoroutineScope(Dispatchers.Unconfined))) } }
        }
        block()
    }

    private fun HttpResponse.setCookie(name: String) = headers.getAll(HttpHeaders.SetCookie).orEmpty().firstOrNull { it.startsWith("$name=") }

    @Test
    fun `refresh with the cookie issues a new access cookie and no token in the body`() = sessionTest {
        val (_, refresh) = newSession()
        val res = client.post("/api/v1/auth/refresh") { cookie(REFRESH_TOKEN_COOKIE, refresh) }
        assertEquals(HttpStatusCode.OK, res.status)
        val access = assertNotNull(res.setCookie(ACCESS_TOKEN_COOKIE))
        assertTrue("HttpOnly" in access && "SameSite=Strict" in access)
        assertFalse("accessToken" in res.bodyAsText() || "refreshToken" in res.bodyAsText(), "tokens must not be in the body")
        val jwt = access.substringAfter("$ACCESS_TOKEN_COOKIE=").substringBefore(";")
        assertEquals(user.id, jwtService.verifyAccessToken(jwt)?.userId)
    }

    @Test
    fun `refresh without a cookie is a 401`() = sessionTest {
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/auth/refresh").status)
    }

    @Test
    fun `refresh with an expired session is a 401 and clears cookies`() = sessionTest {
        val (_, refresh) = newSession(expiresAt = Instant.now().minus(1, ChronoUnit.MINUTES))
        val res = client.post("/api/v1/auth/refresh") { cookie(REFRESH_TOKEN_COOKIE, refresh) }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertTrue(res.setCookie(ACCESS_TOKEN_COOKIE)!!.contains("Max-Age=0"))
    }

    @Test
    fun `logout revokes only this device and refresh then fails`() = sessionTest {
        val (thisDevice, refresh) = newSession()
        val (otherDevice, _) = newSession()

        val res = client.post("/api/v1/auth/logout") { cookie(REFRESH_TOKEN_COOKIE, refresh) }
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.setCookie(ACCESS_TOKEN_COOKIE)!!.contains("Max-Age=0"))
        assertTrue(res.setCookie(REFRESH_TOKEN_COOKIE)!!.contains("Max-Age=0"))

        val active = runBlocking { sessions.findActiveByUserId(user.id) }.map { it.id }
        assertFalse(thisDevice.id in active)
        assertTrue(otherDevice.id in active, "other devices stay logged in")

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/auth/refresh") { cookie(REFRESH_TOKEN_COOKIE, refresh) }.status)
    }

    @Test
    fun `logout works without any valid access token`() = sessionTest {
        val (_, refresh) = newSession()
        val res = client.post("/api/v1/auth/logout") {
            cookie(ACCESS_TOKEN_COOKIE, "expired-or-garbage")
            cookie(REFRESH_TOKEN_COOKIE, refresh)
        }
        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test
    fun `session cookies outlive the access jwt so the page guard keeps the session`() = sessionTest {
        val (_, refresh) = newSession()
        val access = client.post("/api/v1/auth/refresh") { cookie(REFRESH_TOKEN_COOKIE, refresh) }.setCookie(ACCESS_TOKEN_COOKIE)!!
        val maxAge = Regex("Max-Age=(\\d+)").find(access)!!.groupValues[1].toLong()
        assertEquals(config.auth.refreshTokenTtlDays * 24 * 3600, maxAge)
        assertTrue(maxAge > config.auth.accessTokenTtlSeconds)
    }
}
