package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** Maps to db/migration/V80__ask_feedback.sql (AOS-711). [payload] is encrypted (masked question + plan). */
object AskFeedbackTable : Table("ask_feedback") {
    val askId = javaUUID("ask_id")
    val userId = javaUUID("user_id").references(UsersTable.id)
    val helpful = bool("helpful")
    val answerSource = varchar("source", 16)
    val promptVersion = varchar("prompt_version", 32)
    val payload = text("payload")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(askId)
}
