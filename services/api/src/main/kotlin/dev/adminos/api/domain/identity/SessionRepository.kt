package dev.adminos.api.domain.identity

import java.time.Instant
import java.util.UUID

/** A refresh token that was replaced (AOS-620): presenting it again means it was copied. */
data class RotatedToken(val sessionId: UUID, val rotatedAt: Instant)

interface SessionRepository {
    suspend fun findByTokenHash(tokenHash: String): Session?
    suspend fun findById(id: UUID): Session?
    suspend fun findActiveByUserId(userId: UUID): List<Session>
    /** Every session the user ever had, newest first, at most [limit] (for "is this a new device?"). */
    suspend fun findRecentByUserId(userId: UUID, limit: Int): List<Session>
    suspend fun save(session: Session): Session
    suspend fun revoke(sessionId: UUID)
    /** Revokes every active session of [userId]; returns how many. */
    suspend fun revokeAllForUser(userId: UUID): Int
    suspend fun updateLastActive(sessionId: UUID)

    /**
     * Replaces the session's refresh token [oldHash] by [newHash] and remembers [oldHash] as rotated, atomically.
     * @return false if the session's token is no longer [oldHash] (another refresh won the race)
     */
    suspend fun rotate(sessionId: UUID, oldHash: String, newHash: String, at: Instant): Boolean

    /** The session a rotated-out refresh token belonged to, if [tokenHash] is one. */
    suspend fun findRotated(tokenHash: String): RotatedToken?
}
