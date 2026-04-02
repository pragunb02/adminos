package dev.adminos.api.domain.identity

import dev.adminos.api.config.AuthConfig
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class AuthResult(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val user: User,
    val isNewUser: Boolean
)

class AuthService(
    private val userRepository: UserRepository,
    private val sessionRepository: SessionRepository,
    private val jwtService: JwtService,
    private val googleOAuthClient: GoogleOAuthClient,
    private val config: AuthConfig
) {

    suspend fun loginWithGoogle(
        code: String,
        redirectUri: String,
        ipAddress: String?,
        userAgent: String?
    ): AuthResult {
        // Exchange code with Google
        val googleUser = googleOAuthClient.exchangeCode(code, redirectUri)

        // Verify email is verified (Google Identity docs requirement)
        if (googleUser.emailVerified != true) {
            throw UnverifiedEmailException()
        }

        // Find or create user
        val existingUser = userRepository.findByGoogleId(googleUser.sub)
        val isNewUser = existingUser == null

        val user = if (existingUser != null) {
            // Update profile from Google (name/avatar may change)
            userRepository.update(existingUser.copy(
                name = googleUser.name ?: existingUser.name,
                avatarUrl = googleUser.picture ?: existingUser.avatarUrl,
                updatedAt = Instant.now()
            ))
        } else {
            userRepository.save(User(
                email = googleUser.email,
                name = googleUser.name,
                avatarUrl = googleUser.picture,
                googleId = googleUser.sub,
                onboardingStatus = OnboardingStatus.STARTED,
                role = UserRole.OWNER
            ))
        }

        // Generate tokens
        val tokenPair = jwtService.generateTokenPair(user)

        // Create session
        val session = Session(
            userId = user.id,
            tokenHash = JwtService.hashToken(tokenPair.refreshToken),
            ipAddress = ipAddress,
            userAgent = userAgent,
            expiresAt = Instant.now().plus(config.refreshTokenTtlDays, ChronoUnit.DAYS)
        )
        sessionRepository.save(session)

        return AuthResult(
            accessToken = tokenPair.accessToken,
            refreshToken = tokenPair.refreshToken,
            expiresIn = tokenPair.expiresIn,
            user = user,
            isNewUser = isNewUser
        )
    }

    suspend fun refreshToken(refreshToken: String): String {
        val tokenHash = JwtService.hashToken(refreshToken)
        val session = sessionRepository.findByTokenHash(tokenHash)
            ?: throw SessionNotFoundException()

        if (!session.isActive) {
            throw SessionRevokedException()
        }

        val user = userRepository.findById(session.userId)
            ?: throw UserNotFoundException()

        // Update last active
        sessionRepository.updateLastActive(session.id)

        return jwtService.generateAccessToken(user)
    }

    suspend fun logout(userId: UUID, sessionTokenHash: String) {
        val sessions = sessionRepository.findActiveByUserId(userId)
        val session = sessions.find { it.tokenHash == sessionTokenHash }
        if (session != null) {
            sessionRepository.revoke(session.id)
        }
    }

    suspend fun getActiveSessions(userId: UUID): List<Session> {
        return sessionRepository.findActiveByUserId(userId)
    }

    suspend fun revokeSession(userId: UUID, sessionId: UUID) {
        val sessions = sessionRepository.findActiveByUserId(userId)
        val session = sessions.find { it.id == sessionId }
        if (session != null) {
            sessionRepository.revoke(session.id)
        }
    }
}

class SessionNotFoundException : RuntimeException("Session not found")
class SessionRevokedException : RuntimeException("Session has been revoked")
class UserNotFoundException : RuntimeException("User not found")
class UnverifiedEmailException : RuntimeException("Google email is not verified")
