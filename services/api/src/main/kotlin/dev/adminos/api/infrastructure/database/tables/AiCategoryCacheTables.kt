package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/** Maps to db/migration/V28__ai_category_cache.sql (AOS-512). */
object AiCategoryCacheTable : Table("ai_category_cache") {
    val merchantKey = varchar("merchant_key", 120)
    val model = varchar("model", 64)
    val category = varchar("category", 30).nullable()
    val confidence = double("confidence").nullable()
    val reason = varchar("reason", 200).nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(merchantKey)
}
