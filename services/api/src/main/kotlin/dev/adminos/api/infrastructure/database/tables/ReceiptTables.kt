package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** Maps to db/migration/V50__order_receipts.sql (AOS-903). Rows are written by the Gmail worker. */
object OrderReceiptsTable : Table("order_receipts") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id").references(UsersTable.id)
    val app = varchar("app", 40)
    val vertical = varchar("vertical", 20)
    val orderRefHash = char("order_ref_hash", 64)
    val sourceRef = varchar("source_ref", 255)
    val orderedAt = timestamp("ordered_at")
    val status = varchar("status", 16)
    val orderTotal = decimal("order_total", 12, 2).nullable()
    val itemCount = integer("item_count").nullable()
    val paymentMode = varchar("payment_mode", 16)
    val paidAmount = decimal("paid_amount", 12, 2).nullable()
    val matchState = varchar("match_state", 16)
    val transactionId = javaUUID("transaction_id").nullable()
    val matchScore = integer("match_score").nullable()
    val matchReason = varchar("match_reason", 200).nullable()
    val matchedBy = varchar("matched_by", 16).nullable()
    val rejectedTransactionIds = text("rejected_transaction_ids").nullable() // comma-separated (D-10)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object OrderReceiptFeesTable : Table("order_receipt_fees") {
    val receiptId = javaUUID("receipt_id").references(OrderReceiptsTable.id)
    val kind = varchar("kind", 16)
    val amount = decimal("amount", 10, 2)
    override val primaryKey = PrimaryKey(receiptId, kind)
}

object OrderReceiptRefundsTable : Table("order_receipt_refunds") {
    val receiptId = javaUUID("receipt_id").references(OrderReceiptsTable.id)
    val amount = decimal("amount", 12, 2)
    val sourceRef = varchar("source_ref", 255)
    val refundedAt = timestamp("refunded_at")
    override val primaryKey = PrimaryKey(receiptId, amount)
}
