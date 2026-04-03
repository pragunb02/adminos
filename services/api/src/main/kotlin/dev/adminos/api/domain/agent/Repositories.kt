package dev.adminos.api.domain.agent

import java.util.UUID

// ── Anomaly Repository ──

interface AnomalyRepository {
    suspend fun save(anomaly: Anomaly): Anomaly
    suspend fun findById(id: UUID): Anomaly?
    suspend fun findByUserId(
        userId: UUID,
        status: AnomalyStatus? = null,
        cursor: String? = null,
        limit: Int = 20
    ): List<Anomaly>
    suspend fun countByUserId(userId: UUID, status: AnomalyStatus? = null): Long
    suspend fun update(anomaly: Anomaly): Anomaly
}

// ── Briefing Repository ──

interface BriefingRepository {
    suspend fun save(briefing: Briefing): Briefing
    suspend fun findById(id: UUID): Briefing?
    suspend fun findLatestByUserId(userId: UUID): Briefing?
    suspend fun findByUserId(
        userId: UUID,
        cursor: String? = null,
        limit: Int = 20
    ): List<Briefing>
    suspend fun countByUserId(userId: UUID): Long
}

// ── Insight Repository ──

interface InsightRepository {
    suspend fun save(insight: Insight): Insight
    suspend fun findByBriefingId(briefingId: UUID): List<Insight>
    suspend fun findByUserId(userId: UUID, status: InsightStatus? = null): List<Insight>
}
