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

    override suspend fun findByTokenHash(tokenHash: String): Session? =
        sessions.values.find { it.tokenHash == tokenHash }

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
