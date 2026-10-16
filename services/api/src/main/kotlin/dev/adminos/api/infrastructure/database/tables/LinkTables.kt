package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** Maps to db/migration/V13__cross_source_dedup.sql (AOS-207). */
object TransactionLinksTable : Table("transaction_links") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id").references(UsersTable.id)
    val transactionId = javaUUID("transaction_id").references(TransactionsTable.id)
    val relatedTransactionId = javaUUID("related_transaction_id").nullable()
    val ingestEventId = javaUUID("ingest_event_id").nullable()
    val sourceType = varchar("source", 20) // "source" clashes with ColumnSet.source
    val kind = varchar("kind", 20)
    val score = integer("score")
    val reason = text("reason")
    val rulesVersion = varchar("rules_version", 20)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}
