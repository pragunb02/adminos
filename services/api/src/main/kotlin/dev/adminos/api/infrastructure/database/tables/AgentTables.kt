package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed table objects for the Agent domain.
 * Maps 1:1 to infra/migrations/005_create_agent_tables.sql
 */

object BriefingsTable : Table("briefings") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(UsersTable.id)
    val periodStart = date("period_start")
    val periodEnd = date("period_end")
    val type = varchar("type", 20).default("weekly")
    val content = text("content")
    val contentStructured = text("content_structured").nullable() // JSONB
    val totalSpent = decimal("total_spent", 12, 2).nullable()
    val totalIncome = decimal("total_income", 12, 2).nullable()
    val topCategories = text("top_categories").nullable() // JSONB
    val subscriptionsFlagged = integer("subscriptions_flagged").default(0)
    val anomaliesDetected = integer("anomalies_detected").default(0)
    val billsUpcoming = integer("bills_upcoming").default(0)
    val status = varchar("status", 20).default("generated")
    val deliveredAt = timestamp("delivered_at").nullable()
    val openedAt = timestamp("opened_at").nullable()
    val modelUsed = varchar("model_used", 50)
    val promptVersion = varchar("prompt_version", 20)
    val tokensUsed = integer("tokens_used")
    val generationMs = integer("generation_ms").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object InsightsTable : Table("insights") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(UsersTable.id)
    val briefingId = uuid("briefing_id").references(BriefingsTable.id).nullable()
    val type = varchar("type", 30)
    val title = varchar("title", 255)
    val body = text("body")
    val severity = varchar("severity", 20).default("info")
    val entityType = varchar("entity_type", 50).nullable()
    val entityId = uuid("entity_id").nullable()
    val actionType = varchar("action_type", 30).default("none")
    val actionPayload = text("action_payload").nullable() // JSONB
    val status = varchar("status", 20).default("pending")
    val seenAt = timestamp("seen_at").nullable()
    val actedAt = timestamp("acted_at").nullable()
    val dismissedAt = timestamp("dismissed_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object AnomaliesTable : Table("anomalies") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(UsersTable.id)
    val transactionId = uuid("transaction_id").references(TransactionsTable.id)
    val type = varchar("type", 30)
    val confidenceScore = decimal("confidence_score", 3, 2)
    val reason = text("reason")
    val agentExplanation = text("agent_explanation").nullable()
    val status = varchar("status", 20).default("open")
    val resolvedAt = timestamp("resolved_at").nullable()
    val resolvedBy = varchar("resolved_by", 20).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}
