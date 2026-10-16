package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.ingestion.events.*
import dev.adminos.api.infrastructure.database.JsonColumns
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.IngestEventsTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import java.time.Instant
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
                it[paymentRef] = e.paymentRef
                it[metadata] = e.metadata?.let { m -> JsonColumns.encode(m) }
                it[status] = e.status.name.lowercase()
                it[error] = e.error
                it[receivedAt] = e.receivedAt
                it[claimedAt] = e.claimedAt
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

    override suspend fun claim(limit: Int, staleBefore: Instant): List<IngestEvent> = dbQuery {
        val t = IngestEventsTable
        // Rows locked by a concurrent claimer are skipped, so two processors never get the same event.
        val ids = t.select(t.id)
            .where { (t.status eq IngestEventStatus.PENDING.name.lowercase()) or
                ((t.status eq IngestEventStatus.PROCESSING.name.lowercase()) and (t.claimedAt less staleBefore)) }
            .orderBy(t.receivedAt, SortOrder.ASC)
            .limit(limit)
            .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
            .map { it[t.id] }
        if (ids.isEmpty()) return@dbQuery emptyList()
        t.update({ t.id inList ids }) {
            it[status] = IngestEventStatus.PROCESSING.name.lowercase()
            it[claimedAt] = Instant.now()
        }
        t.selectAll().where { t.id inList ids }.orderBy(t.receivedAt, SortOrder.ASC).map { it.toIngestEvent() }
    }

    override suspend fun finish(id: UUID, status: IngestEventStatus, error: String?): Unit = dbQuery {
        IngestEventsTable.update({ IngestEventsTable.id eq id }) {
            it[IngestEventsTable.status] = status.name.lowercase()
            it[IngestEventsTable.error] = error
            it[processedAt] = Instant.now()
        }
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
        paymentRef = this[IngestEventsTable.paymentRef],
        metadata = this[IngestEventsTable.metadata]?.let { JsonColumns.decode(it) },
        status = IngestEventStatus.valueOf(this[IngestEventsTable.status].uppercase()),
        error = this[IngestEventsTable.error],
        receivedAt = this[IngestEventsTable.receivedAt],
        claimedAt = this[IngestEventsTable.claimedAt],
        processedAt = this[IngestEventsTable.processedAt]
    )
}
