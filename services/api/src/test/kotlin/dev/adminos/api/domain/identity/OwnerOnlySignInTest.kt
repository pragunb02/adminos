package dev.adminos.api.domain.identity

import dev.adminos.api.config.AppConfig
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-614 (D-91): only the owner's Google account can sign in, and only the one first pinned for that email. */
class OwnerOnlySignInTest {
    private val owner = "pragun.b@gmail.com"
    private val google = FakeGoogleServer()
    private val users = InMemoryUserRepository()
    private val sessions = InMemorySessionRepository()

    @AfterTest
    fun stop() = google.close()

    private fun service(ownerEmail: String = owner): AuthService {
        val config = identityTestAuthConfig(ownerEmail = ownerEmail)
        return AuthService(users, sessions, JwtService(config), google.client(config), config)
    }

    private suspend fun login(service: AuthService = service()) =
        service.loginWithGoogle("4/0AbCdEf", "http://localhost:3000/auth/callback", "203.0.113.7", "test-agent")

    @Test
    fun `the owner signs in, with any letter case or spaces in the configured email`() = runBlocking<Unit> {
        assertEquals(owner, login(service("  Pragun.B@Gmail.com ")).user.email)
    }

    @Test
    fun `any other google account is refused, and no user or session is created`() = runBlocking<Unit> {
        google.userInfoBody = FakeGoogleServer.userInfo(sub = "stranger-1", email = "someone.else@gmail.com")
        assertFailsWith<NotOwnerException> { login() }
        assertEquals(null, users.findByEmail("someone.else@gmail.com"))
    }

    @Test
    fun `a second google account presenting the owner's email is refused once the owner is pinned`() = runBlocking<Unit> {
        login() // pins the owner's Google account
        google.userInfoBody = FakeGoogleServer.userInfo(sub = "impostor-2", email = owner)
        assertFailsWith<NotOwnerException> { login() }
    }

    @Test
    fun `with no owner configured nobody can sign in`() = runBlocking<Unit> {
        assertFailsWith<NotOwnerException> { login(service(ownerEmail = "")) }
    }

    @Test
    fun `an unverified email is still refused before the owner check`() = runBlocking<Unit> {
        google.userInfoBody = FakeGoogleServer.userInfo(email = owner, emailVerified = false)
        assertFailsWith<UnverifiedEmailException> { login() }
    }

    @Test
    fun `a session of someone who isn't the owner is revoked on refresh`() = runBlocking<Unit> {
        val other = users.save(User(email = "old-user@example.com", googleId = "g-old"))
        val refresh = "refresh-${UUID.randomUUID()}"
        val session = Session(userId = other.id, tokenHash = JwtService.hashToken(refresh), expiresAt = Instant.now().plus(30, ChronoUnit.DAYS))
        sessions.save(session)

        assertFailsWith<NotOwnerException> { service().refreshToken(refresh) }
        assertTrue(sessions.findActiveByUserId(other.id).none { it.id == session.id })
    }

    @Test
    fun `the owner policy normalises, validates, and fails closed`() {
        assertTrue(OwnerPolicy(" A@B.co ").isOwnerEmail("a@b.co"))
        assertFalse(OwnerPolicy("a@b.co").isOwnerEmail(null))
        assertFalse(OwnerPolicy("").isConfigured)
        assertFalse(OwnerPolicy("").isOwnerEmail(""))
        assertTrue(OwnerPolicy.isValid("me@example.com"))
        listOf("me", "me@", "@example.com", "me@example", "a b@example.com", "a@b@c.com").forEach { assertFalse(OwnerPolicy.isValid(it), it) }
    }

    @Test
    fun `OWNER_EMAIL is required with a database, validated, and optional in dev mode`() {
        val env = { vars: Map<String, String> -> { k: String -> vars[k] } }
        assertEquals("me@example.com", AppConfig.ownerEmail(env(mapOf("OWNER_EMAIL" to " me@example.com "))))
        assertEquals("", AppConfig.ownerEmail(env(emptyMap())))
        val missing = assertFailsWith<IllegalStateException> { AppConfig.ownerEmail(env(mapOf("DATABASE_URL" to "jdbc:postgresql://db/adminos"))) }
        assertTrue("OWNER_EMAIL" in missing.message.orEmpty())
        assertFailsWith<IllegalArgumentException> { AppConfig.ownerEmail(env(mapOf("OWNER_EMAIL" to "not-an-email"))) }
    }
}
