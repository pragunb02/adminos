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
    private val config: AuthConfig,
    private val owner: OwnerPolicy = OwnerPolicy(config.ownerEmail)
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

        // Only the owner's Google account, and only the one first pinned for that email (AOS-614, D-91).
        if (!owner.isOwnerEmail(googleUser.email)) throw NotOwnerException()
        val existingUser = userRepository.findByGoogleId(googleUser.sub)
        if (existingUser == null && userRepository.findByEmail(googleUser.email) != null) throw NotOwnerException()
        val isNewUser = existingUser == null

        // A deactivated or deleted account can't sign back in (AOS-610).
        if (existingUser != null && !existingUser.canSignIn()) throw UserInactiveException()

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

        // A deactivated or deleted account loses its sessions (AOS-610).
        if (!user.canSignIn()) {
            sessionRepository.revoke(session.id)
            throw UserInactiveException()
        }
        // So does anyone who isn't the owner, e.g. after OWNER_EMAIL changed (AOS-614).
        if (!owner.isOwnerEmail(user.email)) {
            sessionRepository.revoke(session.id)
            throw NotOwnerException()
        }

        // Update last active
        sessionRepository.updateLastActive(session.id)

        return jwtService.generateAccessToken(user)
    }

    /**
     * Revokes the session that owns [refreshToken] (logout of *this* device only).
     * @return the session's user id, or null if no such active session
     */
    suspend fun logoutByRefreshToken(refreshToken: String): UUID? {
        val session = sessionRepository.findByTokenHash(JwtService.hashToken(refreshToken)) ?: return null
        if (session.isActive) sessionRepository.revoke(session.id)
        return session.userId
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

    /** Revokes one of [userId]'s own active sessions. @return false if they have no such session (AOS-610). */
    suspend fun revokeSession(userId: UUID, sessionId: UUID): Boolean {
        val session = sessionRepository.findActiveByUserId(userId).find { it.id == sessionId } ?: return false
        sessionRepository.revoke(session.id)
        return true
    }
}

class SessionNotFoundException : RuntimeException("Session not found")
class SessionRevokedException : RuntimeException("Session has been revoked")
class UserNotFoundException : RuntimeException("User not found")
class UnverifiedEmailException : RuntimeException("Google email is not verified")
class UserInactiveException : RuntimeException("This account is deactivated")

/** Active and not deleted (AOS-610). */
fun User.canSignIn(): Boolean = isActive && deletedAt == null
