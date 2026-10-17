package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Exposed table objects for the Ingestion domain.
 * Maps 1:1 to db/migration/V3__create_ingestion_tables.sql
 */

object UserConnectionsTable : Table("user_connections") {
    val id = javaUUID("id").autoGenerate()
    val userId = javaUUID("user_id").references(UsersTable.id)
    val sourceType = varchar("source_type", 30)
    val status = varchar("status", 20).default("pending")
    val accessToken = text("access_token").nullable()
    val refreshToken = text("refresh_token").nullable()
    val tokenExpiresAt = timestamp("token_expires_at").nullable()
    val oauthScope = text("oauth_scope").nullable() // stored as comma-separated
    val gmailAddress = varchar("gmail_address", 255).nullable()
    val watchExpiresAt = timestamp("watch_expires_at").nullable() // V26 (AOS-405)
    val watchHistoryId = varchar("watch_history_id", 100).nullable()
    val watchError = text("watch_error").nullable()
    val watchCheckedAt = timestamp("watch_checked_at").nullable()
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
    val id = javaUUID("id").autoGenerate()
    val userId = javaUUID("user_id").references(UsersTable.id)
    val connectionId = javaUUID("connection_id").references(UserConnectionsTable.id)
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
    val id = javaUUID("id").autoGenerate()
    val userId = javaUUID("user_id").references(UsersTable.id)
    val fingerprint = varchar("fingerprint", 64)
    val sourceType = varchar("source_type", 30)
    val entityType = varchar("entity_type", 30).default("transaction")
    val entityId = javaUUID("entity_id")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** Maps to db/migration/V9__create_ingest_events.sql (AOS-113). */
object IngestEventsTable : Table("ingest_events") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id").references(UsersTable.id)
    val sourceType = varchar("source", 20) // "source" clashes with ColumnSet.source
    val sourceRef = varchar("source_ref", 255)
    val occurredAt = timestamp("occurred_at")
    val direction = varchar("direction", 10)
    val amount = decimal("amount", 14, 2)
    val currency = varchar("currency", 3)
    val merchantRaw = varchar("merchant_raw", 255).nullable()
    val accountHint = varchar("account_hint", 4).nullable()
    val paymentMethod = varchar("payment_method", 20).nullable()
    val upiVpa = varchar("upi_vpa", 255).nullable()
    val paymentRef = varchar("payment_ref", 32).nullable()
    val category = varchar("category", 30).nullable()
    val metadata = text("metadata").nullable() // JSONB via JsonColumns
    val status = varchar("status", 20)
    val error = text("error").nullable()
    val receivedAt = timestamp("received_at")
    val claimedAt = timestamp("claimed_at").nullable()
    val processedAt = timestamp("processed_at").nullable()
    override val primaryKey = PrimaryKey(id)
}
