package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** Maps to db/migration/V92__balance_readings.sql (AOS-720). [amount] is encrypted. */
object BalanceReadingsTable : Table("balance_readings") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id").references(UsersTable.id)
    val bankCode = varchar("bank_code", 16).nullable()
    val accountLast4 = char("account_last4", 4)
    val sourceKind = varchar("source", 16)
    val sourceRef = char("source_ref", 64)
    val amount = text("amount")
    val observedAt = timestamp("observed_at")
    val recordedAt = timestamp("recorded_at")
    override val primaryKey = PrimaryKey(id)
}
