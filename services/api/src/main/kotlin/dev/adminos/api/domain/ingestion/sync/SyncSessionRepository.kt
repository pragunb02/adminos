package dev.adminos.api.domain.ingestion.sync

import java.time.Instant
import java.util.UUID

interface SyncSessionRepository {
    suspend fun findById(id: UUID): SyncSession?
    suspend fun findByUserId(userId: UUID, limit: Int = 20): List<SyncSession>

    /** When the connection's latest completed session of one of [types] completed; null if none. */
    suspend fun lastCompletedAt(connectionId: UUID, types: Set<SyncType>): Instant?

    /**
     * The connection's newest session that is still queued (the worker hasn't started it) and was created after
     * [createdAfter]; null if none (AOS-630).
     */
    suspend fun findQueued(connectionId: UUID, createdAfter: Instant): SyncSession?

    suspend fun save(session: SyncSession): SyncSession
    suspend fun update(session: SyncSession): SyncSession
}
