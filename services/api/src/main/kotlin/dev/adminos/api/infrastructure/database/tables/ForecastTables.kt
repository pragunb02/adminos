package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** Maps to db/migration/V93__salary_choices.sql (AOS-722). */
object SalaryChoicesTable : Table("salary_choices") {
    val userId = javaUUID("user_id").references(UsersTable.id)
    val payer = text("payer").nullable()
    val payDay = short("pay_day")
    val amount = decimal("amount", 14, 2)
    val kind = text("kind")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(userId)
}
