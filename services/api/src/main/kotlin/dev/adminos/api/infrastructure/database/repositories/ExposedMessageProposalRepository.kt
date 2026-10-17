package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.fallback.MessageChannel
import dev.adminos.api.domain.ingestion.fallback.MessageProposal
import dev.adminos.api.domain.ingestion.fallback.MessageProposalRepository
import dev.adminos.api.domain.ingestion.fallback.ProposalStatus
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.dbQueryReadCommitted
import dev.adminos.api.infrastructure.database.tables.MessageProposalsTable as T
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

/** AI-read message proposals on Postgres (AOS-705). Every query is scoped to the user. */
class ExposedMessageProposalRepository : MessageProposalRepository {

    // ON CONFLICT DO NOTHING on (user_id, channel, source_ref): a message is proposed at most once.
    override suspend fun saveNew(proposal: MessageProposal): Boolean = dbQuery {
        T.insertIgnore {
            it[id] = proposal.id
            it[userId] = proposal.userId
            it[channel] = proposal.channel.key
            it[sourceRef] = proposal.sourceRef
            it[occurredAt] = proposal.occurredAt
            it[direction] = proposal.direction.name.lowercase()
            it[amount] = proposal.amount
            it[accountLast4] = proposal.accountLast4
            it[merchant] = proposal.merchant
            it[bankCode] = proposal.bankCode
            it[model] = proposal.model
            it[promptVersion] = proposal.promptVersion
            it[status] = proposal.status.name.lowercase()
            it[createdAt] = proposal.createdAt
            it[decidedAt] = proposal.decidedAt
            it[ingestEventId] = proposal.ingestEventId
        }.insertedCount == 1
    }

    override suspend fun exists(userId: UUID, channel: MessageChannel, sourceRef: String): Boolean = dbQuery {
        T.selectAll().where { (T.userId eq userId) and (T.channel eq channel.key) and (T.sourceRef eq sourceRef) }.limit(1).any()
    }

    override suspend fun pending(userId: UUID, limit: Int): List<MessageProposal> = dbQuery {
        T.selectAll().where { (T.userId eq userId) and (T.status eq PENDING) }
            .orderBy(T.occurredAt to SortOrder.DESC, T.id to SortOrder.ASC)
            .limit(limit)
            .map { it.toProposal() }
    }

    override suspend fun find(userId: UUID, id: UUID): MessageProposal? = dbQuery {
        T.selectAll().where { (T.id eq id) and (T.userId eq userId) }.singleOrNull()?.toProposal()
    }

    override suspend fun decide(userId: UUID, id: UUID, status: ProposalStatus, at: Instant, ingestEventId: UUID?): Boolean {
        require(status != ProposalStatus.PENDING) { "A decision confirms or dismisses" }
        // READ COMMITTED with the status in the WHERE: of two concurrent decisions, the second waits, re-reads the row,
        // finds it decided and updates nothing (at REPEATABLE READ it would fail with a serialisation error instead).
        return dbQueryReadCommitted {
            T.update({ (T.id eq id) and (T.userId eq userId) and (T.status eq PENDING) }) {
                it[T.status] = status.name.lowercase()
                it[T.decidedAt] = at
                it[T.ingestEventId] = ingestEventId
            } == 1
        }
    }

    override suspend fun reopen(userId: UUID, id: UUID): Boolean = dbQueryReadCommitted {
        T.update({ (T.id eq id) and (T.userId eq userId) and (T.status eq CONFIRMED) }) {
            it[T.status] = PENDING
            it[T.decidedAt] = null
            it[T.ingestEventId] = null
        } == 1
    }

    private fun ResultRow.toProposal() = MessageProposal(
        id = this[T.id],
        userId = this[T.userId],
        channel = MessageChannel.fromKey(this[T.channel]) ?: error("Unknown channel in message_proposals"),
        sourceRef = this[T.sourceRef],
        occurredAt = this[T.occurredAt],
        direction = IngestDirection.valueOf(this[T.direction].uppercase()),
        amount = this[T.amount],
        accountLast4 = this[T.accountLast4],
        merchant = this[T.merchant],
        bankCode = this[T.bankCode],
        model = this[T.model],
        promptVersion = this[T.promptVersion],
        status = ProposalStatus.valueOf(this[T.status].uppercase()),
        createdAt = this[T.createdAt],
        decidedAt = this[T.decidedAt],
        ingestEventId = this[T.ingestEventId]
    )

    private companion object {
        val PENDING = ProposalStatus.PENDING.name.lowercase()
        val CONFIRMED = ProposalStatus.CONFIRMED.name.lowercase()
    }
}
