package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed table objects for the Financial domain.
 * Maps 1:1 to infra/migrations/004_create_financial_tables.sql
 */

object TransactionsTable : Table("transactions") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(UsersTable.id)
    val sourceType = varchar("source_type", 30)
    val sourceRef = varchar("source_ref", 255).nullable()
    val type = varchar("type", 20)
    val amount = decimal("amount", 12, 2)
    val currency = varchar("currency", 3).default("INR")
    val merchantName = varchar("merchant_name", 255).nullable()
    val merchantRaw = text("merchant_raw").nullable()
    val merchantCategory = varchar("merchant_category", 10).nullable()
    val accountId = uuid("account_id").references(AccountsTable.id).nullable()
    val accountLast4 = varchar("account_last4", 4).nullable()
    val paymentMethod = varchar("payment_method", 20).nullable()
    val upiVpa = varchar("upi_vpa", 255).nullable()
    val category = varchar("category", 30).default("other")
    val subcategory = varchar("subcategory", 100).nullable()
    val categorySource = varchar("category_source", 20).default("rules")
    val isRecurring = bool("is_recurring").default(false)
    val recurringGroupId = uuid("recurring_group_id").nullable()
    val status = varchar("status", 20).default("completed")
    val isAnomaly = bool("is_anomaly").default(false)
    val anomalyId = uuid("anomaly_id").nullable()
    val isVerified = bool("is_verified").default(true)
    val rawEmailId = varchar("raw_email_id", 255).nullable()
    val metadata = text("metadata").nullable() // JSONB stored as text
    val transactedAt = timestamp("transacted_at")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object AccountsTable : Table("accounts") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(UsersTable.id)
    val accountType = varchar("account_type", 20)
    val bankName = varchar("bank_name", 100).nullable()
    val bankCode = varchar("bank_code", 20).nullable()
    val accountLast4 = varchar("account_last4", 4)
    val accountName = varchar("account_name", 255).nullable()
    val isPrimary = bool("is_primary").default(false)
    val currency = varchar("currency", 3).default("INR")
    val connectionId = uuid("connection_id").references(UserConnectionsTable.id).nullable()
    val metadata = text("metadata").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object SubscriptionsTable : Table("subscriptions") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(UsersTable.id)
    val name = varchar("name", 255)
    val merchantName = varchar("merchant_name", 255).nullable()
    val category = varchar("category", 30).default("other")
    val amount = decimal("amount", 10, 2)
    val currency = varchar("currency", 3).default("INR")
    val billingCycle = varchar("billing_cycle", 20)
    val nextBillingDate = date("next_billing_date").nullable()
    val lastBilledDate = date("last_billed_date").nullable()
    val firstBilledDate = date("first_billed_date").nullable()
    val status = varchar("status", 20).default("active")
    val detectionSource = varchar("detection_source", 30)
    val usageStatus = varchar("usage_status", 20).default("unknown")
    val usageSignal = text("usage_signal").nullable()
    val wasteScore = decimal("waste_score", 3, 2).nullable()
    val wasteScoreUpdatedAt = timestamp("waste_score_updated_at").nullable()
    val priceChanged = bool("price_changed").default(false)
    val priceChangePct = decimal("price_change_pct", 5, 2).nullable()
    val isFlagged = bool("is_flagged").default(false)
    val flaggedReason = text("flagged_reason").nullable()
    val flaggedAt = timestamp("flagged_at").nullable()
    val flagDismissedAt = timestamp("flag_dismissed_at").nullable()
    val transactionIds = text("transaction_ids").nullable() // UUID[] stored as text
    val cancellationDraft = text("cancellation_draft").nullable()
    val cancellationDraftGeneratedAt = timestamp("cancellation_draft_generated_at").nullable()
    val metadata = text("metadata").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object BillsTable : Table("bills") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(UsersTable.id)
    val billType = varchar("bill_type", 30)
    val billerName = varchar("biller_name", 255)
    val accountRef = varchar("account_ref", 100).nullable()
    val amount = decimal("amount", 12, 2)
    val minimumDue = decimal("minimum_due", 12, 2).nullable()
    val currency = varchar("currency", 3).default("INR")
    val dueDate = date("due_date")
    val billingPeriodStart = date("billing_period_start").nullable()
    val billingPeriodEnd = date("billing_period_end").nullable()
    val status = varchar("status", 30).default("upcoming")
    val paidAt = timestamp("paid_at").nullable()
    val paidAmount = decimal("paid_amount", 12, 2).nullable()
    val paymentTxnId = uuid("payment_txn_id").references(TransactionsTable.id).nullable()
    val detectionSource = varchar("detection_source", 30)
    val sourceRef = varchar("source_ref", 255).nullable()
    val reminderSent3d = bool("reminder_sent_3d").default(false)
    val reminderSent1d = bool("reminder_sent_1d").default(false)
    val metadata = text("metadata").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}
