package dev.adminos.api.domain.ingestion.sync

import dev.adminos.api.domain.ingestion.SmsRecord
import dev.adminos.api.domain.ingestion.SmsRecordAdapter
import dev.adminos.api.domain.ingestion.events.IngestEventService
import java.time.Instant
import dev.adminos.api.infrastructure.queue.AsynqPublisher
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Handles SMS batch ingestion, sync session creation, and job publishing.
 * When AsynqPublisher is available, jobs are published to Redis for Go workers.
 * When absent (local dev), jobs are only logged.
 */
class IngestionService(
    private val ingestEvents: IngestEventService,
    private val syncSessionRepository: SyncSessionRepository,
    private val publisher: AsynqPublisher? = null
) {
    private val logger = LoggerFactory.getLogger(IngestionService::class.java)

    init {
        if (publisher == null) {
            logger.warn("AsynqPublisher not configured — jobs will be logged but not queued")
        }
    }

    /**
     * SMS adapter: records become common intake events (D-34); the pipeline turns them into
     * transactions. The sync session reports what was accepted vs. already seen.
     */
    suspend fun ingestSmsBatch(
        userId: UUID,
        connectionId: UUID,
        records: List<SmsRecord>
    ): SyncSession {
        records.forEach { record ->
            require(record.rawText == null) { "Raw SMS text is not accepted" }
        }
        val events = records.mapIndexed { i, r -> SmsRecordAdapter.toEvent(userId, r, "batch[$i]") }
        val result = ingestEvents.ingest(events)

        val now = Instant.now()
        val session = SyncSession(
            userId = userId,
            connectionId = connectionId,
            syncType = SyncType.INCREMENTAL,
            status = SyncSessionStatus.COMPLETED,
            totalItems = records.size,
            processedItems = records.size,
            duplicateItems = result.duplicates,
            netNewItems = result.accepted,
            startedAt = now,
            completedAt = now
        )
        syncSessionRepository.save(session)
        return session
    }

    suspend fun getSyncSession(sessionId: UUID): SyncSession? {
        return syncSessionRepository.findById(sessionId)
    }

    /**
     * Queues a Gmail sync for the Go worker. [historyId] is a push notification's (informational:
     * the worker always starts from the history ID stored on the connection, AOS-401). [syncType]
     * records why the sync ran; by default a push with a history ID is incremental.
     * The payload carries strings only, and no key for an absent value.
     */
    suspend fun enqueueGmailIngest(
        userId: UUID,
        connectionId: UUID,
        historyId: String? = null,
        syncType: SyncType? = null
    ): SyncSession {
        val session = SyncSession(
            userId = userId,
            connectionId = connectionId,
            syncType = syncType ?: if (historyId != null) SyncType.INCREMENTAL else SyncType.HISTORICAL,
            status = SyncSessionStatus.QUEUED
        )
        syncSessionRepository.save(session)

        val payload = buildMap {
            put("user_id", userId.toString())
            put("sync_session_id", session.id.toString())
            put("connection_id", connectionId.toString())
            if (historyId != null) put("history_id", historyId)
        }
        publisher?.enqueue(jobType = "gmail_ingest", payload = payload)
            ?: logger.info("Gmail ingest logged (no queue): session={}, type={}", session.id, session.syncType)

        return session
    }

    suspend fun getUserSyncSessions(userId: UUID, limit: Int = 20): List<SyncSession> {
        return syncSessionRepository.findByUserId(userId, limit)
    }
}
