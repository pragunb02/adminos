package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** Maps to the AOS-705 migration (`message_proposals`): AI-read transactions waiting in Review, no message text. */
object MessageProposalsTable : Table("message_proposals") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id")
    val channel = varchar("channel", 10)
    val sourceRef = varchar("source_ref", 255)
    val occurredAt = timestamp("occurred_at")
    val direction = varchar("direction", 6)
    val amount = decimal("amount", 14, 2)
    val accountLast4 = char("account_last4", 4).nullable()
    val merchant = varchar("merchant", 60).nullable()
    val bankCode = varchar("bank_code", 20).nullable()
    val model = varchar("model", 100)
    val promptVersion = varchar("prompt_version", 100)
    val status = varchar("status", 10)
    val createdAt = timestamp("created_at")
    val decidedAt = timestamp("decided_at").nullable()
    val ingestEventId = javaUUID("ingest_event_id").nullable()
    /** AOS-706: the masked shape, learnt from on confirmation. */
    val shape = varchar("shape", 1000).nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Maps to the AOS-706 migration (`message_templates`): learnt message formats as masked shapes, no values. */
object MessageTemplatesTable : Table("message_templates") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id")
    val channel = varchar("channel", 10)
    val bankCode = varchar("bank_code", 20).nullable()
    val direction = varchar("direction", 6)
    val shape = varchar("shape", 1000)
    val status = varchar("status", 10)
    val seen = integer("seen")
    val hits = integer("hits")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}
