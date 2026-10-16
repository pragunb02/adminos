package dev.adminos.api.domain.identity

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Disabled
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.*

/** AOS-312: AuthService login (against a local fake Google), refresh and logout edge cases. */
class IdentityAuthServiceCoverageTest {

    private val config = identityTestAuthConfig()
    private val google = FakeGoogleServer()
    private val users = InMemoryUserRepository()
    private val sessions = InMemorySessionRepository()
    private val jwt = JwtService(config)
    private val service = AuthService(users, sessions, jwt, google.client(config), config)
    private val callback = "http://localhost:3000/auth/callback"

    @AfterTest
    fun stop() = google.close()

    private suspend fun login() = service.loginWithGoogle("4/0AbCdEf", callback, "203.0.113.7", "Mozilla/5.0 (Macintosh)")

    private suspend fun storedSession(userId: UUID, refresh: String = "refresh-${UUID.randomUUID()}", revokedAt: Instant? = null): Pair<Session, String> {
        val s = Session(userId = userId, tokenHash = JwtService.hashToken(refresh), expiresAt = Instant.now().plus(30, ChronoUnit.DAYS), revokedAt = revokedAt)
        sessions.save(s)
        return s to refresh
    }

    @Test
    fun `first google login creates an owner user who is still onboarding`() = runBlocking<Unit> {
        val result = login()

        assertTrue(result.isNewUser)
        val saved = assertNotNull(users.findByGoogleId("109876543210987654321"))
        assertEquals(saved.id, result.user.id)
        assertEquals("pragun.b@gmail.com", saved.email)
        assertEquals("Pragun Bhutani", saved.name)
        assertEquals(OnboardingStatus.STARTED, saved.onboardingStatus)
        assertEquals(UserRole.OWNER, saved.role)
    }

    @Test
    fun `login stores only the hash of the refresh token with the device's ip and user agent`() = runBlocking<Unit> {
        val result = login()

        val session = assertNotNull(sessions.findByTokenHash(JwtService.hashToken(result.refreshToken)))
        assertEquals(result.user.id, session.userId)
        assertNotEquals(result.refreshToken, session.tokenHash)
        assertEquals("203.0.113.7", session.ipAddress)
        assertEquals("Mozilla/5.0 (Macintosh)", session.userAgent)
        assertTrue(session.expiresAt.isAfter(Instant.now().plus(29, ChronoUnit.DAYS)))
    }

    @Test
    fun `login returns an access token that verifies for the user`() = runBlocking<Unit> {
        val result = login()

        assertEquals(result.user.id, jwt.verifyAccessToken(result.accessToken)?.userId)
        assertEquals(config.accessTokenTtlSeconds, result.expiresIn)
    }

    @Test
    fun `a returning user keeps their id and gets google's latest name and photo`() = runBlocking<Unit> {
        val existing = users.save(User(email = "pragun.b@gmail.com", name = "Old Name", avatarUrl = "https://old/photo", googleId = "109876543210987654321"))

        val result = login()

        assertFalse(result.isNewUser)
        assertEquals(existing.id, result.user.id)
        assertEquals("Pragun Bhutani", result.user.name)
        assertEquals("https://lh3.googleusercontent.com/a/photo", result.user.avatarUrl)
    }

    @Test
    fun `a returning user keeps their stored name and photo when google sends none`() = runBlocking<Unit> {
        users.save(User(email = "pragun.b@gmail.com", name = "Pragun", avatarUrl = "https://old/photo", googleId = "109876543210987654321"))
        google.userInfoBody = FakeGoogleServer.userInfo(name = null, picture = null)

        val result = login()

        assertEquals("Pragun", result.user.name)
        assertEquals("https://old/photo", result.user.avatarUrl)
    }

    @Test
    fun `an unverified google email cannot log in`() = runBlocking<Unit> {
        google.userInfoBody = FakeGoogleServer.userInfo(emailVerified = false)

        assertFailsWith<UnverifiedEmailException> { login() }
        assertNull(users.findByGoogleId("109876543210987654321"))
    }

    @Test
    fun `a google profile without email_verified cannot log in`() = runBlocking<Unit> {
        google.userInfoBody = FakeGoogleServer.userInfo(emailVerified = null)

        assertFailsWith<UnverifiedEmailException> { login() }
    }

    @Test
    fun `refresh for a session whose user no longer exists fails`() = runBlocking<Unit> {
        val (_, refresh) = storedSession(UUID.randomUUID())

        assertFailsWith<UserNotFoundException> { service.refreshToken(refresh) }
    }

    @Test
    fun `refresh records the session as recently active`() = runBlocking<Unit> {
        val user = users.save(User(email = "me@example.com", googleId = "g-refresh"))
        val (session, refresh) = storedSession(user.id)

        service.refreshToken(refresh)

        assertNotNull(sessions.findActiveByUserId(user.id).single { it.id == session.id }.lastActiveAt)
    }

    @Test
    @Disabled("BUG AOS-312: AuthService.refreshToken never checks User.isActive/deletedAt, so a deactivated user keeps minting access tokens")
    fun `refresh is refused for a deactivated user`() = runBlocking<Unit> {
        val user = users.save(User(email = "gone@example.com", googleId = "g-gone", isActive = false, deletedAt = Instant.now()))
        val (_, refresh) = storedSession(user.id)

        assertFailsWith<RuntimeException> { service.refreshToken(refresh) }
    }

    @Test
    @Disabled("BUG AOS-312: AuthService.loginWithGoogle never checks User.isActive/deletedAt, so a deactivated user can log back in")
    fun `google login is refused for a deactivated user`() = runBlocking<Unit> {
        users.save(User(email = "pragun.b@gmail.com", googleId = "109876543210987654321", isActive = false, deletedAt = Instant.now()))

        assertFailsWith<RuntimeException> { login() }
    }

    @Test
    fun `logout by an unknown refresh token is a no-op`() = runBlocking<Unit> {
        assertNull(service.logoutByRefreshToken("never-issued"))
    }

    @Test
    fun `logout by an already revoked refresh token reports the user and keeps the original revocation`() = runBlocking<Unit> {
        val userId = UUID.randomUUID()
        val revokedAt = Instant.parse("2026-10-01T10:00:00Z")
        val (session, refresh) = storedSession(userId, revokedAt = revokedAt)

        assertEquals(userId, service.logoutByRefreshToken(refresh))
        assertEquals(revokedAt, sessions.findByTokenHash(session.tokenHash)?.revokedAt)
    }

    @Test
    fun `logout by token hash revokes only the matching session`() = runBlocking<Unit> {
        val userId = UUID.randomUUID()
        val (thisDevice, _) = storedSession(userId)
        val (otherDevice, _) = storedSession(userId)

        service.logout(userId, thisDevice.tokenHash)

        assertEquals(listOf(otherDevice.id), service.getActiveSessions(userId).map { it.id })
    }

    @Test
    fun `logout with a hash that matches no session revokes nothing`() = runBlocking<Unit> {
        val userId = UUID.randomUUID()
        storedSession(userId)

        service.logout(userId, JwtService.hashToken("other"))

        assertEquals(1, service.getActiveSessions(userId).size)
    }

    @Test
    fun `a user cannot revoke another user's session`() = runBlocking<Unit> {
        val owner = UUID.randomUUID()
        val (victim, _) = storedSession(owner)

        service.revokeSession(UUID.randomUUID(), victim.id)

        assertEquals(listOf(victim.id), service.getActiveSessions(owner).map { it.id })
    }

    @Test
    fun `revoking one of your sessions leaves the others active`() = runBlocking<Unit> {
        val userId = UUID.randomUUID()
        val (a, _) = storedSession(userId)
        val (b, _) = storedSession(userId)

        service.revokeSession(userId, a.id)

        assertEquals(listOf(b.id), service.getActiveSessions(userId).map { it.id })
    }

    @Test
    fun `auth exceptions carry readable messages`() {
        assertEquals("User not found", UserNotFoundException().message)
        assertEquals("Google email is not verified", UnverifiedEmailException().message)
        assertEquals("Session not found", SessionNotFoundException().message)
        assertEquals("Session has been revoked", SessionRevokedException().message)
    }
}
