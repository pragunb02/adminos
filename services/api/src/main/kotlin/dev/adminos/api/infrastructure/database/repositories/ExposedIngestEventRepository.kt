package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.ingestion.events.*
import dev.adminos.api.infrastructure.database.JsonColumns
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.IngestEventsTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.util.UUID

class ExposedIngestEventRepository : IngestEventRepository {

    /** `INSERT … ON CONFLICT DO NOTHING` per event; the unique (user, source, source_ref) decides. */
    override suspend fun saveNew(events: List<IngestEvent>): Int = dbQuery {
        events.sumOf { e ->
            IngestEventsTable.insertIgnore {
                it[id] = e.id
                it[userId] = e.userId
                it[sourceType] = e.source.name.lowercase()
                it[sourceRef] = e.sourceRef
                it[occurredAt] = e.occurredAt
                it[direction] = e.direction.name.lowercase()
                it[amount] = e.amount
                it[currency] = e.currency
                it[merchantRaw] = e.merchantRaw
                it[accountHint] = e.accountHint
                it[paymentMethod] = e.paymentMethod?.name?.lowercase()
                it[upiVpa] = e.upiVpa
                it[metadata] = e.metadata?.let { m -> JsonColumns.encode(m) }
                it[status] = e.status.name.lowercase()
                it[error] = e.error
                it[receivedAt] = e.receivedAt
                it[processedAt] = e.processedAt
            }.insertedCount
        }
    }

    override suspend fun findByUser(userId: UUID, limit: Int): List<IngestEvent> = dbQuery {
        IngestEventsTable.selectAll().where { IngestEventsTable.userId eq userId }
            .orderBy(IngestEventsTable.occurredAt, SortOrder.DESC).limit(limit).map { it.toIngestEvent() }
    }

    override suspend fun findPending(limit: Int): List<IngestEvent> = dbQuery {
        IngestEventsTable.selectAll().where { IngestEventsTable.status eq IngestEventStatus.PENDING.name.lowercase() }
            .orderBy(IngestEventsTable.receivedAt, SortOrder.ASC).limit(limit).map { it.toIngestEvent() }
    }

    private fun ResultRow.toIngestEvent() = IngestEvent(
        id = this[IngestEventsTable.id],
        userId = this[IngestEventsTable.userId],
        source = IngestSource.valueOf(this[IngestEventsTable.sourceType].uppercase()),
        sourceRef = this[IngestEventsTable.sourceRef],
        occurredAt = this[IngestEventsTable.occurredAt],
        direction = IngestDirection.valueOf(this[IngestEventsTable.direction].uppercase()),
        amount = this[IngestEventsTable.amount],
        currency = this[IngestEventsTable.currency],
        merchantRaw = this[IngestEventsTable.merchantRaw],
        accountHint = this[IngestEventsTable.accountHint],
        paymentMethod = this[IngestEventsTable.paymentMethod]?.let { PaymentMethod.valueOf(it.uppercase()) },
        upiVpa = this[IngestEventsTable.upiVpa],
        metadata = this[IngestEventsTable.metadata]?.let { JsonColumns.decode(it) },
        status = IngestEventStatus.valueOf(this[IngestEventsTable.status].uppercase()),
        error = this[IngestEventsTable.error],
        receivedAt = this[IngestEventsTable.receivedAt],
        processedAt = this[IngestEventsTable.processedAt]
    )
}
