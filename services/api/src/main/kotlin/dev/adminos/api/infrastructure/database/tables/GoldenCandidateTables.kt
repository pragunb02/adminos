package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** Maps to the `golden_candidates` migration (AOS-708): the user's local, scrubbed golden set. */
object GoldenCandidatesTable : Table("golden_candidates") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id").references(UsersTable.id)
    val merchantRaw = varchar("merchant_raw", 120)
    val paymentMethod = varchar("payment_method", 20).nullable()
    val direction = varchar("direction", 10)
    val category = varchar("category", 30)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
