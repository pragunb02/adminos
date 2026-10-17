package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestamp

/** Maps to db/migration/V41__splitwise_splits.sql (AOS-911). */
object SplitImportsTable : Table("split_imports") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id").references(UsersTable.id)
    val sourceName = varchar("source", 20) // "source" clashes with ColumnSet.source
    val rowsRead = integer("rows_read")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object SplitEntriesTable : Table("split_entries") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id").references(UsersTable.id)
    val importId = javaUUID("import_id").references(SplitImportsTable.id)
    val sourceName = varchar("source", 20)
    val sourceRef = varchar("source_ref", 64)
    val kind = varchar("kind", 10)
    val effect = varchar("effect", 15)
    val state = varchar("state", 12)
    val entryDate = date("entry_date")
    val descriptionEnc = text("description_enc")
    val counterpartyEnc = text("counterparty_enc").nullable()
    val splitwiseCategory = varchar("splitwise_category", 60).nullable()
    val cost = decimal("cost", 12, 2)
    val currency = varchar("currency", 3)
    val ownerNet = decimal("owner_net", 12, 2)
    val ownerShare = decimal("owner_share", 12, 2)
    val bankAmount = decimal("bank_amount", 12, 2)
    val transactionId = javaUUID("transaction_id").nullable()
    val createdTransaction = bool("created_transaction")
    val priorNature = varchar("prior_nature", 20).nullable()
    val priorCategory = varchar("prior_category", 30).nullable()
    val priorSubcategory = varchar("prior_subcategory", 100).nullable()
    val priorCategorySource = varchar("prior_category_source", 20).nullable()
    val rejectedIds = text("rejected_ids")
    val score = integer("score").nullable()
    val reason = varchar("reason", 200).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}
