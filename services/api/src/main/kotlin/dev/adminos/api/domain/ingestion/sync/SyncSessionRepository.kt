package dev.adminos.api.domain.ingestion.sync

import java.util.UUID

interface SyncSessionRepository {
    suspend fun findById(id: UUID): SyncSession?
    suspend fun findByUserId(userId: UUID, limit: Int = 20): List<SyncSession>
    suspend fun save(session: SyncSession): SyncSession
    suspend fun update(session: SyncSession): SyncSession
}
