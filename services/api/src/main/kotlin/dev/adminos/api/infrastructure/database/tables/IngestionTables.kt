package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed table objects for the Ingestion domain.
 * Maps 1:1 to infra/migrations/003_create_ingestion_tables.sql
 */

object UserConnectionsTable : Table("user_connections") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(UsersTable.id)
    val sourceType = varchar("source_type", 30)
    val status = varchar("status", 20).default("pending")
    val accessToken = text("access_token").nullable()
    val refreshToken = text("refresh_token").nullable()
    val tokenExpiresAt = timestamp("token_expires_at").nullable()
    val oauthScope = text("oauth_scope").nullable() // stored as comma-separated
    val gmailAddress = varchar("gmail_address", 255).nullable()
    val pubsubExpiry = timestamp("pubsub_expiry").nullable()
    val historyId = varchar("history_id", 100).nullable()
    val lastSyncedAt = timestamp("last_synced_at").nullable()
    val lastSyncStatus = varchar("last_sync_status", 20).nullable()
    val lastError = text("last_error").nullable()
    val nextSyncAt = timestamp("next_sync_at").nullable()
    val totalSynced = integer("total_synced").default(0)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object SyncSessionsTable : Table("sync_sessions") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(UsersTable.id)
    val connectionId = uuid("connection_id").references(UserConnectionsTable.id)
    val syncType = varchar("sync_type", 20)
    val status = varchar("status", 20).default("queued")
    val totalItems = integer("total_items").default(0)
    val processedItems = integer("processed_items").default(0)
    val failedItems = integer("failed_items").default(0)
    val duplicateItems = integer("duplicate_items").default(0)
    val netNewItems = integer("net_new_items").default(0)
    val startedAt = timestamp("started_at").nullable()
    val completedAt = timestamp("completed_at").nullable()
    val errorDetails = text("error_details").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object IngestionFingerprintsTable : Table("ingestion_fingerprints") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(UsersTable.id)
    val fingerprint = varchar("fingerprint", 64)
    val sourceType = varchar("source_type", 30)
    val entityType = varchar("entity_type", 30).default("transaction")
    val entityId = uuid("entity_id")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
