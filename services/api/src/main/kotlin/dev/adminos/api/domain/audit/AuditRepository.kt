package dev.adminos.api.domain.audit

import java.util.UUID

interface AuditRepository {
    suspend fun save(auditLog: AuditLog)
    suspend fun findByUserId(userId: UUID, limit: Int = 50): List<AuditLog>
    suspend fun findByEntity(entityType: String, entityId: UUID): List<AuditLog>
}
