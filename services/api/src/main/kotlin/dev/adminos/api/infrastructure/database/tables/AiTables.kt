package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** Maps to the AOS-702 AI-layer migration (`ai_calls`): the audit log of model calls, with no content. */
object AiCallsTable : Table("ai_calls") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id")
    val feature = varchar("feature", 40)
    val provider = varchar("provider", 20)
    val model = varchar("model", 100)
    val promptVersion = varchar("prompt_version", 100)
    val inputTokens = long("input_tokens")
    val outputTokens = long("output_tokens")
    val costMicros = long("cost_micros")
    val outcome = varchar("outcome", 10)
    val code = varchar("code", 48).nullable()
    val latencyMs = long("latency_ms")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** Maps to the AOS-702 AI-layer migration (`ai_spend`): the monthly spend ledger behind the cost cap. */
object AiSpendTable : Table("ai_spend") {
    val month = varchar("month", 7)
    val spentMicros = long("spent_micros")
    val reservedMicros = long("reserved_micros")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(month)
}
