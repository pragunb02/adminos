package dev.adminos.api.domain.ingestion.sync

import dev.adminos.api.domain.ingestion.SmsRecord
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Handles SMS batch ingestion, sync session creation, and job publishing.
 * In MVP, jobs are tracked in-memory. Redis queue integration comes in task 3.4.
 */
class IngestionService(
    private val syncSessionRepository: SyncSessionRepository
) {
    private val logger = LoggerFactory.getLogger(IngestionService::class.java)

    suspend fun ingestSmsBatch(
        userId: UUID,
        connectionId: UUID,
        records: List<SmsRecord>
    ): SyncSession {
        records.forEach { record ->
            require(record.rawText == null) { "Raw SMS text is not accepted" }
        }

        val session = SyncSession(
            userId = userId,
            connectionId = connectionId,
            syncType = SyncType.INCREMENTAL,
            status = SyncSessionStatus.QUEUED,
            totalItems = records.size
        )
        syncSessionRepository.save(session)

        logger.info("SMS batch queued: session={}, items={}", session.id, records.size)
        return session
    }

    suspend fun getSyncSession(sessionId: UUID): SyncSession? {
        return syncSessionRepository.findById(sessionId)
    }

    suspend fun enqueueGmailIngest(
        userId: UUID,
        connectionId: UUID,
        historyId: String? = null
    ): SyncSession {
        val session = SyncSession(
            userId = userId,
            connectionId = connectionId,
            syncType = if (historyId != null) SyncType.INCREMENTAL else SyncType.HISTORICAL,
            status = SyncSessionStatus.QUEUED
        )
        syncSessionRepository.save(session)

        logger.info("Gmail ingest queued: session={}, historyId={}", session.id, historyId)
        return session
    }

    suspend fun enqueuePdfParse(
        userId: UUID,
        connectionId: UUID,
        storageKey: String,
        fileName: String
    ): SyncSession {
        val session = SyncSession(
            userId = userId,
            connectionId = connectionId,
            syncType = SyncType.MANUAL,
            status = SyncSessionStatus.QUEUED
        )
        syncSessionRepository.save(session)

        logger.info("PDF parse queued: session={}, file={}, key={}", session.id, fileName, storageKey)
        return session
    }

    suspend fun getUserSyncSessions(userId: UUID, limit: Int = 20): List<SyncSession> {
        return syncSessionRepository.findByUserId(userId, limit)
    }
}
