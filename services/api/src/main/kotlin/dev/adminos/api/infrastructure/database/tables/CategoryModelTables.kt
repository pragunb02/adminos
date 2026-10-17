package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** Maps to the `category_models` migration (AOS-707): one learned categoriser per user. */
object CategoryModelsTable : Table("category_models") {
    val userId = javaUUID("user_id").references(UsersTable.id)
    val algorithm = varchar("algorithm", 40)
    val formatVersion = integer("format_version")
    val blob = binary("model")
    val checksum = char("checksum", 64)
    val mode = varchar("mode", 10)
    val trainingRows = integer("training_rows")
    val corrections = integer("corrections")
    val categories = integer("categories")
    val heldOut = integer("held_out")
    val heldOutPrecision = double("held_out_precision").nullable()
    val calibrationError = double("calibration_error").nullable()
    val temperature = double("temperature")
    val trainedAt = timestamp("trained_at")
    override val primaryKey = PrimaryKey(userId)
}
