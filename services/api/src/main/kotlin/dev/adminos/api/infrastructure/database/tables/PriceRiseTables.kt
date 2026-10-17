package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestamp

/** Subscription price rises, one per (subscription, new price in whole units) (V34, AOS-715). */
object SubscriptionPriceRisesTable : Table("subscription_price_rises") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id").references(UsersTable.id)
    val subscriptionId = javaUUID("subscription_id").references(SubscriptionsTable.id)
    val oldAmount = decimal("old_amount", 14, 2)
    val newAmount = decimal("new_amount", 14, 2)
    val priceKey = long("price_key")
    val currency = varchar("currency", 3)
    val chargedOn = date("charged_on")
    val transactionId = javaUUID("transaction_id")
    val notifiedAt = timestamp("notified_at").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
