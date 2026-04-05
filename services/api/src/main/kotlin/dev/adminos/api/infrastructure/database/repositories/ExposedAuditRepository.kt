package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.audit.*
import dev.adminos.api.infrastructure.database.tables.AuditLogsTable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.UUID

class ExposedAuditRepository : AuditRepository {

    override suspend fun save(auditLog: AuditLog): Unit = newSuspendedTransaction {
        AuditLogsTable.insert {
            it[id] = auditLog.id
            it[userId] = auditLog.userId
            it[actor] = auditLog.actor.name.lowercase()
            it[action] = auditLog.action
            it[entityType] = auditLog.entityType
            it[entityId] = auditLog.entityId
            it[beforeState] = auditLog.beforeState?.let { s -> Json.encodeToString(JsonObject.serializer(), s) }
            it[afterState] = auditLog.afterState?.let { s -> Json.encodeToString(JsonObject.serializer(), s) }
            it[ipAddress] = auditLog.ipAddress
            it[deviceId] = auditLog.deviceId
            it[metadata] = auditLog.metadata?.let { m -> Json.encodeToString(JsonObject.serializer(), m) }
            it[createdAt] = auditLog.createdAt
        }
    }

    override suspend fun findByUserId(userId: UUID, limit: Int): List<AuditLog> = newSuspendedTransaction {
        AuditLogsTable.select { AuditLogsTable.userId eq userId }
            .orderBy(AuditLogsTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toAuditLog() }
    }

    override suspend fun findByEntity(entityType: String, entityId: UUID): List<AuditLog> =
        newSuspendedTransaction {
            AuditLogsTable.select {
                (AuditLogsTable.entityType eq entityType) and
                    (AuditLogsTable.entityId eq entityId)
            }.orderBy(AuditLogsTable.createdAt, SortOrder.DESC)
                .map { it.toAuditLog() }
        }

    private fun ResultRow.toAuditLog() = AuditLog(
        id = this[AuditLogsTable.id],
        userId = this[AuditLogsTable.userId],
        actor = ActorType.valueOf(this[AuditLogsTable.actor].uppercase()),
        action = this[AuditLogsTable.action],
        entityType = this[AuditLogsTable.entityType],
        entityId = this[AuditLogsTable.entityId],
        beforeState = this[AuditLogsTable.beforeState]?.let { Json.decodeFromString<JsonObject>(it) },
        afterState = this[AuditLogsTable.afterState]?.let { Json.decodeFromString<JsonObject>(it) },
        ipAddress = this[AuditLogsTable.ipAddress],
        deviceId = this[AuditLogsTable.deviceId],
        metadata = this[AuditLogsTable.metadata]?.let { Json.decodeFromString<JsonObject>(it) },
        createdAt = this[AuditLogsTable.createdAt]
    )
}
