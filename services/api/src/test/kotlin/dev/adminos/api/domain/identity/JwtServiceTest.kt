package dev.adminos.api.domain.identity

import dev.adminos.api.config.AuthConfig
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Property test: JWT issued by JwtService can always be verified by the same service.
 * Validates Requirements 1.3, 1.6
 */
class JwtServiceTest {

    private val config = AuthConfig(
        jwtSecret = "test-secret-must-be-at-least-32-bytes-long",
        jwtIssuer = "adminos-test",
        accessTokenTtlSeconds = 3600,
        refreshTokenTtlDays = 30,
        googleClientId = "test-client-id",
        googleClientSecret = "test-client-secret",
        googlePubsubAudience = ""
    )

    private val jwtService = JwtService(config)

    // --- Property: JWT round-trip consistency ---

    @Test
    fun `JWT issued for a user can always be verified and returns same claims`() {
        // Arrange — generate multiple users with different data
        val users = listOf(
            createUser(email = "alice@example.com", role = UserRole.OWNER),
            createUser(email = "bob@example.com", role = UserRole.ADMIN),
            createUser(email = "charlie@example.com", role = UserRole.READONLY),
        )

        for (user in users) {
            // Act — generate and verify
            val token = jwtService.generateAccessToken(user)
            val claims = jwtService.verifyAccessToken(token)

            // Assert — round-trip preserves all claims
            assertNotNull(claims, "Token for ${user.email} should verify successfully")
            assertEquals(user.id, claims.userId, "userId should match for ${user.email}")
            assertEquals(user.email, claims.email, "email should match for ${user.email}")
            assertEquals(user.role.name.lowercase(), claims.role, "role should match for ${user.email}")
        }
    }

    @Test
    fun `JWT token pair generates distinct access and refresh tokens`() {
        val user = createUser()

        val pair = jwtService.generateTokenPair(user)

        assertNotNull(pair.accessToken)
        assertNotNull(pair.refreshToken)
        assert(pair.accessToken != pair.refreshToken) { "Access and refresh tokens must be different" }
        assertEquals(config.accessTokenTtlSeconds, pair.expiresIn)
    }

    @Test
    fun `two token pairs for same user produce different refresh tokens`() {
        val user = createUser()

        val pair1 = jwtService.generateTokenPair(user)
        val pair2 = jwtService.generateTokenPair(user)

        assert(pair1.refreshToken != pair2.refreshToken) { "Each token pair must have a unique refresh token" }
    }

    // --- Negative cases ---

    @Test
    fun `tampered token fails verification`() {
        val user = createUser()
        val token = jwtService.generateAccessToken(user)

        // Tamper with the token payload
        val parts = token.split(".")
        val tampered = parts[0] + "." + parts[1] + "x" + "." + parts[2]

        val claims = jwtService.verifyAccessToken(tampered)
        assertNull(claims, "Tampered token should not verify")
    }

    @Test
    fun `token signed with different secret fails verification`() {
        val otherConfig = config.copy(jwtSecret = "completely-different-secret-key-here")
        val otherService = JwtService(otherConfig)

        val user = createUser()
        val token = otherService.generateAccessToken(user)

        val claims = jwtService.verifyAccessToken(token)
        assertNull(claims, "Token from different secret should not verify")
    }

    @Test
    fun `expired token fails verification`() {
        // Create a service with 0-second TTL
        val expiredConfig = config.copy(accessTokenTtlSeconds = 0)
        val expiredService = JwtService(expiredConfig)

        val user = createUser()
        val token = expiredService.generateAccessToken(user)

        // Token is already expired
        val claims = jwtService.verifyAccessToken(token)
        assertNull(claims, "Expired token should not verify")
    }

    @Test
    fun `garbage string fails verification gracefully`() {
        val claims = jwtService.verifyAccessToken("not.a.jwt")
        assertNull(claims, "Garbage input should return null, not throw")
    }

    @Test
    fun `empty string fails verification gracefully`() {
        val claims = jwtService.verifyAccessToken("")
        assertNull(claims, "Empty string should return null, not throw")
    }

    // --- Token hashing ---

    @Test
    fun `hashToken produces consistent SHA256 for same input`() {
        val token = "test-refresh-token-value"
        val hash1 = JwtService.hashToken(token)
        val hash2 = JwtService.hashToken(token)

        assertEquals(hash1, hash2, "Same input must produce same hash")
        assertEquals(64, hash1.length, "SHA256 hex string must be 64 chars")
    }

    @Test
    fun `hashToken produces different hashes for different inputs`() {
        val hash1 = JwtService.hashToken("token-a")
        val hash2 = JwtService.hashToken("token-b")

        assert(hash1 != hash2) { "Different tokens must produce different hashes" }
    }

    // --- Helpers ---

    private fun createUser(
        email: String = "test@example.com",
        role: UserRole = UserRole.OWNER
    ) = User(
        id = UUID.randomUUID(),
        email = email,
        name = "Test User",
        googleId = "google_${UUID.randomUUID()}",
        role = role
    )
}
