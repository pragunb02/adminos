package dev.adminos.api.domain.ingestion.sync

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemorySyncSessionRepository : SyncSessionRepository {
    private val sessions = ConcurrentHashMap<UUID, SyncSession>()

    override suspend fun findById(id: UUID): SyncSession? = sessions[id]

    override suspend fun findByUserId(userId: UUID, limit: Int): List<SyncSession> =
        sessions.values
            .filter { it.userId == userId }
            .sortedByDescending { it.createdAt }
            .take(limit)

    override suspend fun save(session: SyncSession): SyncSession {
        sessions[session.id] = session
        return session
    }

    override suspend fun update(session: SyncSession): SyncSession {
        sessions[session.id] = session.copy(updatedAt = Instant.now())
        return sessions[session.id]!!
    }
}
