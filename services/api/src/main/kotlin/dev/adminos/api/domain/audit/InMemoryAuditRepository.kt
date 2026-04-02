package dev.adminos.api.domain.audit

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * In-memory audit repository for development/testing.
 * Will be replaced with Exposed/PostgreSQL implementation.
 */
class InMemoryAuditRepository : AuditRepository {
    private val logs = ConcurrentLinkedDeque<AuditLog>()

    override suspend fun save(auditLog: AuditLog) {
        logs.addFirst(auditLog)
    }

    override suspend fun findByUserId(userId: UUID, limit: Int): List<AuditLog> =
        logs.filter { it.userId == userId }.take(limit)

    override suspend fun findByEntity(entityType: String, entityId: UUID): List<AuditLog> =
        logs.filter { it.entityType == entityType && it.entityId == entityId }
}
