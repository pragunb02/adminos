package dev.adminos.api.domain.ingestion.events

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
}
