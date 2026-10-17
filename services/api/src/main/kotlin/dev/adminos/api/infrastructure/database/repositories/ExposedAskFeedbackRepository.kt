package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.ask.AskFeedback
import dev.adminos.api.domain.ask.AskFeedbackRepository
import dev.adminos.api.domain.ask.AskSource
import dev.adminos.api.infrastructure.crypto.TokenEncryptor
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.AskFeedbackTable as T
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.UUID

/**
 * Ask feedback on Postgres (AOS-711, V80). The masked question and the plan are encrypted together with
 * [TokenEncryptor] (AES-256-GCM), bound to `ask_feedback:<askId>:payload`, so a payload copied to another row fails.
 */
class ExposedAskFeedbackRepository(private val encryptor: TokenEncryptor) : AskFeedbackRepository {

    override suspend fun save(feedback: AskFeedback) {
        val payload = JsonObject(mapOf(
            "question" to JsonPrimitive(feedback.question),
            "plan" to (feedback.planJson?.let(::JsonPrimitive) ?: JsonNull)
        )).toString()
        val sealed = encryptor.encrypt(payload, context(feedback.askId))
        dbQuery {
            T.upsert(T.askId) {
                it[askId] = feedback.askId
                it[userId] = feedback.userId
                it[helpful] = feedback.helpful
                it[answerSource] = feedback.source.key
                it[promptVersion] = feedback.promptVersion
                it[T.payload] = sealed
                it[createdAt] = feedback.at
            }
        }
    }

    override suspend fun forUser(userId: UUID): List<AskFeedback> = dbQuery {
        T.selectAll().where { T.userId eq userId }.orderBy(T.createdAt, SortOrder.DESC).map { it.toFeedback() }
    }

    private fun ResultRow.toFeedback(): AskFeedback {
        val id = this[T.askId]
        val payload = Json.parseToJsonElement(encryptor.decrypt(this[T.payload], context(id))).jsonObject
        return AskFeedback(
            askId = id, userId = this[T.userId], helpful = this[T.helpful],
            question = payload.getValue("question").jsonPrimitive.content,
            planJson = (payload["plan"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            source = AskSource.entries.first { it.key == this[T.answerSource] },
            promptVersion = this[T.promptVersion], at = this[T.createdAt]
        )
    }

    private fun context(askId: UUID) = "ask_feedback:$askId:payload"
}
