package dev.adminos.api.domain.identity

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementations for development/testing.
 * Will be replaced with Exposed/PostgreSQL implementations in task 3.1.
 */

class InMemoryUserRepository : UserRepository {
    private val users = ConcurrentHashMap<UUID, User>()

    override suspend fun findById(id: UUID): User? = users[id]

    override suspend fun findByGoogleId(googleId: String): User? =
        users.values.find { it.googleId == googleId }

    override suspend fun findByEmail(email: String): User? =
        users.values.find { it.email == email }

    override suspend fun findByOnboardingIncomplete(): List<User> =
        users.values.filter { it.onboardingStatus != OnboardingStatus.COMPLETED && it.isActive }

    override suspend fun findActive(): List<User> = users.values.filter { it.canSignIn() }

    override suspend fun save(user: User): User {
        users[user.id] = user
        return user
    }

    override suspend fun update(user: User): User {
        users[user.id] = user.copy(updatedAt = Instant.now())
        return users[user.id]!!
    }
}

class InMemorySessionRepository : SessionRepository {
    private val sessions = ConcurrentHashMap<UUID, Session>()
    private val rotated = ConcurrentHashMap<String, RotatedToken>()

    override suspend fun findByTokenHash(tokenHash: String): Session? =
        sessions.values.find { it.tokenHash == tokenHash }

    override suspend fun findById(id: UUID): Session? = sessions[id]

    override suspend fun findRecentByUserId(userId: UUID, limit: Int): List<Session> =
        sessions.values.filter { it.userId == userId }.sortedByDescending { it.createdAt }.take(limit)

    override suspend fun revokeAllForUser(userId: UUID): Int {
        val now = Instant.now()
        var n = 0
        for (s in sessions.values.filter { it.userId == userId && it.isActive }) {
            if (sessions.replace(s.id, s, s.copy(revokedAt = now))) n++
        }
        return n
    }

    override suspend fun rotate(sessionId: UUID, oldHash: String, newHash: String, at: Instant): Boolean {
        var won = false
        sessions.computeIfPresent(sessionId) { _, s ->
            if (s.tokenHash == oldHash) { won = true; s.copy(tokenHash = newHash) } else s
        }
        if (won) rotated[oldHash] = RotatedToken(sessionId, at)
        return won
    }

    override suspend fun findRotated(tokenHash: String): RotatedToken? = rotated[tokenHash]

    override suspend fun markSecondFactor(sessionId: UUID, at: Instant) {
        sessions.computeIfPresent(sessionId) { _, s -> s.copy(secondFactorAt = at) }
    }

    override suspend fun findActiveByUserId(userId: UUID): List<Session> =
        sessions.values.filter { it.userId == userId && it.isActive }

    override suspend fun save(session: Session): Session {
        sessions[session.id] = session
        return session
    }

    override suspend fun revoke(sessionId: UUID) {
        sessions.computeIfPresent(sessionId) { _, s -> s.copy(revokedAt = Instant.now()) }
    }

    override suspend fun updateLastActive(sessionId: UUID) {
        sessions.computeIfPresent(sessionId) { _, s -> s.copy(lastActiveAt = Instant.now()) }
    }
}
