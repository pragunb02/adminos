package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** Maps to db/migration/V40__lens_overrides.sql (AOS-901). */
object LensOverridesTable : Table("lens_overrides") {
    val userId = javaUUID("user_id").references(UsersTable.id)
    val lensId = varchar("lens_id", 40)
    val merchantKey = varchar("merchant_key", 255)
    val merchant = varchar("merchant", 255)
    val include = bool("include")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(userId, lensId, merchantKey)
}
