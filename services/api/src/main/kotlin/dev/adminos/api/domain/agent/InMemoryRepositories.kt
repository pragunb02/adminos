package dev.adminos.api.domain.agent

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// ── In-Memory Anomaly Repository ──

class InMemoryAnomalyRepository : AnomalyRepository {
    private val store = ConcurrentHashMap<UUID, Anomaly>()

    override suspend fun save(anomaly: Anomaly): Anomaly {
        store[anomaly.id] = anomaly
        return anomaly
    }

    override suspend fun findById(id: UUID): Anomaly? = store[id]

    override suspend fun findByUserId(
        userId: UUID, status: AnomalyStatus?, cursor: String?, limit: Int
    ): List<Anomaly> {
        var results = store.values.filter { it.userId == userId }
        status?.let { s -> results = results.filter { it.status == s } }
        results = results.sortedByDescending { it.createdAt }
        cursor?.let { c ->
            val cursorInstant = Instant.parse(c)
            results = results.filter { it.createdAt < cursorInstant }
        }
        return results.take(limit)
    }

    override suspend fun countByUserId(userId: UUID, status: AnomalyStatus?): Long {
        var results = store.values.filter { it.userId == userId }
        status?.let { s -> results = results.filter { it.status == s } }
        return results.size.toLong()
    }

    override suspend fun update(anomaly: Anomaly): Anomaly {
        store[anomaly.id] = anomaly.copy(updatedAt = Instant.now())
        return store[anomaly.id]!!
    }
}

// ── In-Memory Briefing Repository ──

class InMemoryBriefingRepository : BriefingRepository {
    private val store = ConcurrentHashMap<UUID, Briefing>()

    override suspend fun save(briefing: Briefing): Briefing {
        store[briefing.id] = briefing
        return briefing
    }

    override suspend fun findById(id: UUID): Briefing? = store[id]

    override suspend fun findLatestByUserId(userId: UUID): Briefing? =
        store.values.filter { it.userId == userId }
            .maxByOrNull { it.createdAt }

    override suspend fun findByUserId(
        userId: UUID, cursor: String?, limit: Int
    ): List<Briefing> {
        var results = store.values.filter { it.userId == userId }
        results = results.sortedByDescending { it.createdAt }
        cursor?.let { c ->
            val cursorInstant = Instant.parse(c)
            results = results.filter { it.createdAt < cursorInstant }
        }
        return results.take(limit)
    }

    override suspend fun countByUserId(userId: UUID): Long =
        store.values.count { it.userId == userId }.toLong()
}

// ── In-Memory Insight Repository ──

class InMemoryInsightRepository : InsightRepository {
    private val store = ConcurrentHashMap<UUID, Insight>()

    override suspend fun save(insight: Insight): Insight {
        store[insight.id] = insight
        return insight
    }

    override suspend fun findByBriefingId(briefingId: UUID): List<Insight> =
        store.values.filter { it.briefingId == briefingId }
            .sortedByDescending { it.createdAt }

    override suspend fun findByUserId(userId: UUID, status: InsightStatus?): List<Insight> {
        var results = store.values.filter { it.userId == userId }
        status?.let { s -> results = results.filter { it.status == s } }
        return results.sortedByDescending { it.createdAt }
    }
}
