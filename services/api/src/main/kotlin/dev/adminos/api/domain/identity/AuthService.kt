package dev.adminos.api.domain.identity

import dev.adminos.api.config.AuthConfig
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class AuthResult(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val user: User,
    val isNewUser: Boolean,
    /** The session this sign-in created (AOS-620). */
    val sessionId: UUID? = null
)

/** The outcome of a refresh (AOS-620): a new access token and, unless another tab just rotated, a new refresh token. */
data class RefreshResult(val accessToken: String, val refreshToken: String?) {
    override fun toString() = "RefreshResult(…)" // both are bearer secrets
}

class AuthService(
    private val userRepository: UserRepository,
    private val sessionRepository: SessionRepository,
    private val jwtService: JwtService,
    private val googleOAuthClient: GoogleOAuthClient,
    private val config: AuthConfig,
    private val owner: OwnerPolicy = OwnerPolicy(config.ownerEmail),
    private val alerts: SignInAlerts = SignInAlerts(null)
) {

    suspend fun loginWithGoogle(
        code: String,
        redirectUri: String,
        ipAddress: String?,
        userAgent: String?,
        /** The PKCE verifier of the sign-in state this code answers (AOS-618). */
        codeVerifier: String? = null
    ): AuthResult {
        // Exchange code with Google
        val googleUser = googleOAuthClient.exchangeCode(code, redirectUri, codeVerifier)

        // Verify email is verified (Google Identity docs requirement)
        if (googleUser.emailVerified != true) {
            refusedAlert(googleUser.email)
            throw UnverifiedEmailException()
        }

        // Only the owner's Google account, and only the one first pinned for that email (AOS-614, D-91).
        if (!owner.isOwnerEmail(googleUser.email)) {
            refusedAlert(googleUser.email)
            throw NotOwnerException()
        }
        val existingUser = userRepository.findByGoogleId(googleUser.sub)
        if (existingUser == null && userRepository.findByEmail(googleUser.email) != null) {
            refusedAlert(googleUser.email)
            throw NotOwnerException()
        }
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

        // New device or address? Compared with the sessions this user had before (AOS-620).
        val device = DeviceLabel.of(userAgent)
        val earlier = sessionRepository.findRecentByUserId(user.id, RECENT_SESSIONS)
        val known = earlier.any { DeviceLabel.of(it.userAgent) == device && it.ipAddress == ipAddress }

        // The session id goes into the access token (`sid`), so revoking the session ends the token at once.
        val sessionId = UUID.randomUUID()
        val tokenPair = jwtService.generateTokenPair(user, sessionId)
        sessionRepository.save(Session(
            id = sessionId,
            userId = user.id,
            tokenHash = JwtService.hashToken(tokenPair.refreshToken),
            ipAddress = ipAddress,
            userAgent = userAgent,
            expiresAt = Instant.now().plus(config.refreshTokenTtlDays, ChronoUnit.DAYS)
        ))
        if (!known) alerts.newSignIn(user.id, device)

        return AuthResult(
            accessToken = tokenPair.accessToken,
            refreshToken = tokenPair.refreshToken,
            expiresIn = tokenPair.expiresIn,
            user = user,
            isNewUser = isNewUser,
            sessionId = sessionId
        )
    }

    /** The owner to tell about a refused sign-in: the pinned account, if it has signed in before. */
    private suspend fun refusedAlert(email: String?) {
        val ownerUser = owner.email?.let { userRepository.findByEmail(it) }
        alerts.refused(ownerUser?.id, email)
    }

    /**
     * Refresh-token rotation with reuse detection (AOS-620, AOS-121, RFC 9700 §4.14):
     * - the current token → a new access token **and a new refresh token**; the old one is remembered as rotated;
     * - a rotated token within [REUSE_GRACE] of its rotation (two tabs refreshing at once) → a new access token
     *   only, nothing rotated;
     * - a rotated token later than that → it was copied: the whole session is revoked and the owner alerted.
     */
    suspend fun refreshToken(refreshToken: String): RefreshResult {
        val tokenHash = JwtService.hashToken(refreshToken)
        val now = Instant.now()
        val session = sessionRepository.findByTokenHash(tokenHash)
            ?: return afterRotation(tokenHash, now)

        if (!session.isActive) {
            throw SessionRevokedException()
        }
        val user = checkedUser(session)

        val next = jwtService.newRefreshToken()
        val rotated = sessionRepository.rotate(session.id, tokenHash, JwtService.hashToken(next), now)
        sessionRepository.updateLastActive(session.id)
        // Lost a race with another tab: it rotated first. This token is now "just rotated": access only.
        return RefreshResult(jwtService.generateAccessToken(user, session.id), next.takeIf { rotated })
    }

    private suspend fun afterRotation(tokenHash: String, now: Instant): RefreshResult {
        val rotated = sessionRepository.findRotated(tokenHash) ?: throw SessionNotFoundException()
        val session = sessionRepository.findById(rotated.sessionId) ?: throw SessionNotFoundException()
        if (!session.isActive) throw SessionRevokedException()
        if (Duration.between(rotated.rotatedAt, now) <= REUSE_GRACE) {
            return RefreshResult(jwtService.generateAccessToken(checkedUser(session), session.id), null)
        }
        sessionRepository.revoke(session.id)
        alerts.sessionKeyReused(session.userId, DeviceLabel.of(session.userAgent))
        throw RefreshTokenReusedException()
    }

    /** The session's user, if they may still be signed in; otherwise the session is revoked (AOS-610, AOS-614). */
    private suspend fun checkedUser(session: Session): User {
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
        return user
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

    /** Signs [userId] out everywhere, or everywhere but [except] (AOS-620). @return how many sessions ended */
    suspend fun revokeAll(userId: UUID, except: UUID? = null): Int {
        if (except == null) return sessionRepository.revokeAllForUser(userId)
        val others = sessionRepository.findActiveByUserId(userId).filter { it.id != except }
        others.forEach { sessionRepository.revoke(it.id) }
        return others.size
    }

    /** Is [sessionId] an active session of [userId]? Checked on every request that carries a `sid` (AOS-620). */
    suspend fun isActiveSession(userId: UUID, sessionId: UUID): Boolean =
        sessionRepository.findById(sessionId)?.let { it.userId == userId && it.isActive } == true

    /** What the session may do: live and passkey-verified → everything; live but not yet verified → only the passkey step (AOS-617). */
    suspend fun sessionAccess(userId: UUID, sessionId: UUID): SessionAccess {
        val s = sessionRepository.findById(sessionId)?.takeIf { it.userId == userId && it.isActive }
            ?: return SessionAccess.DENIED
        return if (s.secondFactorAt != null) SessionAccess.FULL
        else SessionAccess.PENDING_SECOND_FACTOR
    }

    companion object {
        /** Two tabs refreshing at the same moment both present the same token; the slower one gets this long. */
        val REUSE_GRACE: Duration = Duration.ofSeconds(30)
        private const val RECENT_SESSIONS = 50
    }
}

class SessionNotFoundException : RuntimeException("Session not found")
class SessionRevokedException : RuntimeException("Session has been revoked")
/** A rotated-out refresh token came back after the grace period: it was copied (AOS-620). */
class RefreshTokenReusedException : RuntimeException("Refresh token reused")
class UserNotFoundException : RuntimeException("User not found")
class UnverifiedEmailException : RuntimeException("Google email is not verified")
class UserInactiveException : RuntimeException("This account is deactivated")

/** Active and not deleted (AOS-610). */
fun User.canSignIn(): Boolean = isActive && deletedAt == null
