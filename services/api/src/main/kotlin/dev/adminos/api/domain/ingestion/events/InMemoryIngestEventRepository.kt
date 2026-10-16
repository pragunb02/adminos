package dev.adminos.api.domain.ingestion.events

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemoryIngestEventRepository : IngestEventRepository {
    private val byKey = ConcurrentHashMap<Triple<UUID, IngestSource, String>, IngestEvent>()

    override suspend fun saveNew(events: List<IngestEvent>): Int =
        events.count { e -> byKey.putIfAbsent(Triple(e.userId, e.source, e.sourceRef), e) == null }

    override suspend fun findByUser(userId: UUID, limit: Int): List<IngestEvent> =
        byKey.values.filter { it.userId == userId }.sortedByDescending { it.occurredAt }.take(limit)

    override suspend fun findPending(limit: Int): List<IngestEvent> =
        byKey.values.filter { it.status == IngestEventStatus.PENDING }.sortedBy { it.receivedAt }.take(limit)

    private val claimLock = Mutex()

    override suspend fun claim(limit: Int, staleBefore: Instant): List<IngestEvent> = claimLock.withLock {
        val now = Instant.now()
        byKey.entries
            .filter { (_, e) -> e.status == IngestEventStatus.PENDING || (e.status == IngestEventStatus.PROCESSING && (e.claimedAt ?: now) < staleBefore) }
            .sortedBy { it.value.receivedAt }
            .take(limit)
            .map { (key, e) -> e.copy(status = IngestEventStatus.PROCESSING, claimedAt = now).also { byKey[key] = it } }
    }

    override suspend fun finish(id: UUID, status: IngestEventStatus, error: String?) {
        byKey.entries.firstOrNull { it.value.id == id }?.let { (key, e) ->
            byKey[key] = e.copy(status = status, error = error, processedAt = Instant.now())
        }
    }
}
