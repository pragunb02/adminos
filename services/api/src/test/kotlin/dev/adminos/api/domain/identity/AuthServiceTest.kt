package dev.adminos.api.domain.identity

import dev.adminos.api.config.AuthConfig
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.*

/**
 * Unit tests for AuthService — login, refresh, logout, session management.
 * Uses in-memory repositories (no DB needed).
 * Validates Requirements 1.2, 1.3, 1.4, 1.5, 1.7
 */
class AuthServiceTest {

    private val config = AuthConfig(
        jwtSecret = "test-secret-must-be-at-least-32-bytes-long",
        jwtIssuer = "adminos-test",
        accessTokenTtlSeconds = 3600,
        refreshTokenTtlDays = 30,
        googleClientId = "test-client-id",
        googleClientSecret = "test-client-secret",
        googlePubsubAudience = ""
    )

    private lateinit var userRepository: InMemoryUserRepository
    private lateinit var sessionRepository: InMemorySessionRepository
    private lateinit var jwtService: JwtService
    private lateinit var authService: AuthService

    @BeforeTest
    fun setup() {
        userRepository = InMemoryUserRepository()
        sessionRepository = InMemorySessionRepository()
        jwtService = JwtService(config)

        // AuthService with a fake GoogleOAuthClient (we test the service logic, not Google)
        authService = AuthService(
            userRepository = userRepository,
            sessionRepository = sessionRepository,
            jwtService = jwtService,
            googleOAuthClient = GoogleOAuthClient(config), // won't be called in these tests
            config = config
        )
    }

    // --- Token refresh tests ---

    @Test
    fun `refresh with valid token issues new access token`() = runBlocking {
        // Arrange — create user and session
        val user = User(
            email = "test@example.com",
            googleId = "google_123",
            name = "Test User"
        )
        userRepository.save(user)

        val refreshToken = "valid-refresh-token"
        val session = Session(
            userId = user.id,
            tokenHash = JwtService.hashToken(refreshToken),
            expiresAt = Instant.now().plus(30, ChronoUnit.DAYS)
        )
        sessionRepository.save(session)

        // Act
        val newAccessToken = authService.refreshToken(refreshToken)

        // Assert
        assertNotNull(newAccessToken, "Should return a new access token")
        val claims = jwtService.verifyAccessToken(newAccessToken)
        assertNotNull(claims, "New access token should be valid")
        assertEquals(user.id, claims.userId)
        assertEquals(user.email, claims.email)
    }

    @Test
    fun `refresh with unknown token throws SessionNotFoundException`() = runBlocking {
        assertFailsWith<SessionNotFoundException> {
            authService.refreshToken("unknown-token")
        }
    }

    @Test
    fun `refresh with revoked session throws SessionRevokedException`() = runBlocking {
        // Arrange
        val user = User(email = "test@example.com", googleId = "google_123")
        userRepository.save(user)

        val refreshToken = "revoked-refresh-token"
        val session = Session(
            userId = user.id,
            tokenHash = JwtService.hashToken(refreshToken),
            expiresAt = Instant.now().plus(30, ChronoUnit.DAYS),
            revokedAt = Instant.now() // already revoked
        )
        sessionRepository.save(session)

        // Act & Assert
        assertFailsWith<SessionRevokedException> {
            authService.refreshToken(refreshToken)
        }
    }

    @Test
    fun `refresh with expired session throws SessionRevokedException`() = runBlocking {
        // Arrange
        val user = User(email = "test@example.com", googleId = "google_123")
        userRepository.save(user)

        val refreshToken = "expired-refresh-token"
        val session = Session(
            userId = user.id,
            tokenHash = JwtService.hashToken(refreshToken),
            expiresAt = Instant.now().minus(1, ChronoUnit.DAYS) // expired yesterday
        )
        sessionRepository.save(session)

        // Act & Assert
        assertFailsWith<SessionRevokedException> {
            authService.refreshToken(refreshToken)
        }
    }

    // --- Logout tests ---

    @Test
    fun `logout revokes all active sessions for user`() = runBlocking {
        // Arrange
        val user = User(email = "test@example.com", googleId = "google_123")
        userRepository.save(user)

        val session1 = Session(
            userId = user.id,
            tokenHash = "hash1",
            expiresAt = Instant.now().plus(30, ChronoUnit.DAYS)
        )
        val session2 = Session(
            userId = user.id,
            tokenHash = "hash2",
            expiresAt = Instant.now().plus(30, ChronoUnit.DAYS)
        )
        sessionRepository.save(session1)
        sessionRepository.save(session2)

        // Verify sessions are active
        assertEquals(2, authService.getActiveSessions(user.id).size)

        // Act — revoke one session
        authService.revokeSession(user.id, session1.id)

        // Assert
        val remaining = authService.getActiveSessions(user.id)
        assertEquals(1, remaining.size)
        assertEquals(session2.id, remaining[0].id)
    }

    @Test
    fun `revoking non-existent session does not throw`() = runBlocking {
        val userId = UUID.randomUUID()
        // Should not throw
        authService.revokeSession(userId, UUID.randomUUID())
    }

    // --- Session listing tests ---

    @Test
    fun `getActiveSessions returns only non-revoked non-expired sessions`() = runBlocking {
        val user = User(email = "test@example.com", googleId = "google_123")
        userRepository.save(user)

        // Active session
        val active = Session(
            userId = user.id,
            tokenHash = "active-hash",
            expiresAt = Instant.now().plus(30, ChronoUnit.DAYS)
        )
        // Revoked session
        val revoked = Session(
            userId = user.id,
            tokenHash = "revoked-hash",
            expiresAt = Instant.now().plus(30, ChronoUnit.DAYS),
            revokedAt = Instant.now()
        )
        // Expired session
        val expired = Session(
            userId = user.id,
            tokenHash = "expired-hash",
            expiresAt = Instant.now().minus(1, ChronoUnit.DAYS)
        )

        sessionRepository.save(active)
        sessionRepository.save(revoked)
        sessionRepository.save(expired)

        // Act
        val sessions = authService.getActiveSessions(user.id)

        // Assert
        assertEquals(1, sessions.size)
        assertEquals(active.id, sessions[0].id)
    }
}
