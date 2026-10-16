package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** Maps to db/migration/V15__category_rules.sql (AOS-210). */
object CategoryRulesTable : Table("category_rules") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id").references(UsersTable.id)
    val matchType = varchar("match_type", 20)
    val matchValue = varchar("match_value", 255)
    val category = varchar("category", 30)
    val subcategory = varchar("subcategory", 100).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}
