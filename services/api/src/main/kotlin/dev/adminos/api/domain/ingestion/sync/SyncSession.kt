package dev.adminos.api.domain.ingestion.sync

import java.time.Instant
import java.util.UUID

data class SyncSession(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val connectionId: UUID,
    val syncType: SyncType,
    val status: SyncSessionStatus = SyncSessionStatus.QUEUED,
    val totalItems: Int = 0,
    val processedItems: Int = 0,
    val failedItems: Int = 0,
    val duplicateItems: Int = 0,
    val netNewItems: Int = 0,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val errorDetails: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

enum class SyncType { HISTORICAL, INCREMENTAL, MANUAL, CRON_FALLBACK }
enum class SyncSessionStatus { QUEUED, IN_PROGRESS, COMPLETED, FAILED, PARTIAL }
