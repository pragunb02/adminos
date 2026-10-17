package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** Confirmed merchant merges (V46, AOS-709). */
object MerchantAliasesTable : Table("merchant_aliases") {
    val userId = javaUUID("user_id").references(UsersTable.id)
    val aliasKey = varchar("alias_key", 255)
    val canonicalKey = varchar("canonical_key", 255)
    val canonicalName = varchar("canonical_name", 255)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(userId, aliasKey)
}

/** Pairs the user said are different merchants (V46, AOS-709). */
object MerchantMergeRejectionsTable : Table("merchant_merge_rejections") {
    val userId = javaUUID("user_id").references(UsersTable.id)
    val firstKey = varchar("first_key", 255)
    val secondKey = varchar("second_key", 255)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(userId, firstKey, secondKey)
}
