package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.fallback.MessageChannel
import dev.adminos.api.domain.ingestion.fallback.templates.MessageShape
import dev.adminos.api.domain.ingestion.fallback.templates.MessageTemplate
import dev.adminos.api.domain.ingestion.fallback.templates.MessageTemplateRepository
import dev.adminos.api.domain.ingestion.fallback.templates.TemplateStatus
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.dbQueryReadCommitted
import dev.adminos.api.infrastructure.database.tables.MessageTemplatesTable as T
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

/** Learnt message formats on Postgres (AOS-706). Every query is scoped to the user. */
class ExposedMessageTemplateRepository : MessageTemplateRepository {

    override suspend fun save(template: MessageTemplate): Unit = dbQuery {
        T.insert {
            it[id] = template.id
            it[userId] = template.userId
            it[channel] = template.channel.key
            it[bankCode] = template.bankCode
            it[direction] = template.direction.name.lowercase()
            it[shape] = template.shape.text
            it[status] = template.status.name.lowercase()
            it[seen] = template.seen
            it[hits] = template.hits
            it[createdAt] = template.createdAt
            it[updatedAt] = template.updatedAt
        }
    }

    override suspend fun update(template: MessageTemplate): Boolean = dbQuery {
        T.update({ (T.id eq template.id) and (T.userId eq template.userId) }) {
            it[shape] = template.shape.text
            it[status] = template.status.name.lowercase()
            it[seen] = template.seen
            it[hits] = template.hits
            it[updatedAt] = template.updatedAt
        } == 1
    }

    private fun sender(userId: UUID, channel: MessageChannel, bankCode: String?): Op<Boolean> =
        (T.userId eq userId) and (T.channel eq channel.key) and (if (bankCode == null) T.bankCode.isNull() else T.bankCode eq bankCode)

    override suspend fun forFormat(userId: UUID, channel: MessageChannel, bankCode: String?, direction: IngestDirection): List<MessageTemplate> = dbQuery {
        T.selectAll().where { sender(userId, channel, bankCode) and (T.direction eq direction.name.lowercase()) }
            .orderBy(T.createdAt to SortOrder.ASC).map { it.toTemplate() }
    }

    override suspend fun approved(userId: UUID, channel: MessageChannel, bankCode: String?): List<MessageTemplate> = dbQuery {
        T.selectAll().where { sender(userId, channel, bankCode) and (T.status eq APPROVED) }
            .orderBy(T.hits to SortOrder.DESC, T.createdAt to SortOrder.ASC).map { it.toTemplate() }
    }

    override suspend fun proposed(userId: UUID, limit: Int): List<MessageTemplate> = dbQuery {
        T.selectAll().where { (T.userId eq userId) and (T.status eq PROPOSED) }
            .orderBy(T.seen to SortOrder.DESC, T.createdAt to SortOrder.ASC).limit(limit).map { it.toTemplate() }
    }

    override suspend fun decide(userId: UUID, id: UUID, status: TemplateStatus, at: Instant): Boolean {
        require(status == TemplateStatus.APPROVED || status == TemplateStatus.REJECTED) { "A decision approves or rejects" }
        // READ COMMITTED with the status in the WHERE: of two concurrent decisions exactly one updates the row.
        return dbQueryReadCommitted {
            T.update({ (T.id eq id) and (T.userId eq userId) and (T.status eq PROPOSED) }) {
                it[T.status] = status.name.lowercase()
                it[T.updatedAt] = at
            } == 1
        }
    }

    override suspend fun hit(id: UUID): Unit = dbQueryReadCommitted {
        T.update({ T.id eq id }) { it[hits] = hits + 1 }
    }

    private fun ResultRow.toTemplate() = MessageTemplate(
        id = this[T.id],
        userId = this[T.userId],
        channel = MessageChannel.fromKey(this[T.channel]) ?: error("Unknown channel in message_templates"),
        bankCode = this[T.bankCode],
        direction = IngestDirection.valueOf(this[T.direction].uppercase()),
        shape = MessageShape(this[T.shape]),
        status = TemplateStatus.valueOf(this[T.status].uppercase()),
        seen = this[T.seen],
        hits = this[T.hits],
        createdAt = this[T.createdAt],
        updatedAt = this[T.updatedAt]
    )

    private companion object {
        val PROPOSED = TemplateStatus.PROPOSED.name.lowercase()
        val APPROVED = TemplateStatus.APPROVED.name.lowercase()
    }
}
