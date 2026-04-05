package dev.adminos.api.domain.ingestion.sync

import dev.adminos.api.domain.ingestion.SmsRecord
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
    private val syncSessionRepository: SyncSessionRepository,
    private val publisher: AsynqPublisher? = null
) {
    private val logger = LoggerFactory.getLogger(IngestionService::class.java)

    init {
        if (publisher == null) {
            logger.warn("AsynqPublisher not configured — jobs will be logged but not queued")
        }
    }

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

        // Publish to Redis queue if available
        publisher?.enqueue(
            jobType = "sms_process",
            payload = mapOf(
                "user_id" to userId.toString(),
                "sync_session_id" to session.id.toString(),
                "connection_id" to connectionId.toString(),
                "records" to Json.encodeToString(records.map { r ->
                    mapOf(
                        "merchant" to r.merchant,
                        "amount" to r.amount.toString(),
                        "date" to r.date,
                        "accountLast4" to r.accountLast4,
                        "paymentMethod" to r.paymentMethod
                    )
                })
            )
        ) ?: logger.info("SMS batch logged (no queue): session={}, items={}", session.id, records.size)

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

        publisher?.enqueue(
            jobType = "gmail_ingest",
            payload = mapOf(
                "user_id" to userId.toString(),
                "sync_session_id" to session.id.toString(),
                "connection_id" to connectionId.toString(),
                "history_id" to historyId
            )
        ) ?: logger.info("Gmail ingest logged (no queue): session={}, historyId={}", session.id, historyId)

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

        publisher?.enqueue(
            jobType = "pdf_parse",
            payload = mapOf(
                "user_id" to userId.toString(),
                "sync_session_id" to session.id.toString(),
                "connection_id" to connectionId.toString(),
                "storage_key" to storageKey,
                "file_name" to fileName
            )
        ) ?: logger.info("PDF parse logged (no queue): session={}, file={}", session.id, fileName)

        return session
    }

    suspend fun getUserSyncSessions(userId: UUID, limit: Int = 20): List<SyncSession> {
        return syncSessionRepository.findByUserId(userId, limit)
    }
}
