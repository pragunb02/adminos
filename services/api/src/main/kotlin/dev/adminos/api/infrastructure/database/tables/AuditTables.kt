package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed table objects for the Audit domain.
 * Maps 1:1 to infra/migrations/007_create_audit_tables.sql
 */

object AuditLogsTable : Table("audit_logs") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(UsersTable.id).nullable()
    val actor = varchar("actor", 20)
    val action = varchar("action", 100)
    val entityType = varchar("entity_type", 50)
    val entityId = uuid("entity_id").nullable()
    val beforeState = text("before_state").nullable() // JSONB
    val afterState = text("after_state").nullable() // JSONB
    val ipAddress = varchar("ip_address", 45).nullable()
    val deviceId = uuid("device_id").nullable()
    val metadata = text("metadata").nullable() // JSONB
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
