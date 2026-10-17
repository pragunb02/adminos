package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.mandates.*
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.MandatesTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

/** Postgres [MandateRepository] (AOS-502). */
class ExposedMandateRepository : MandateRepository {
    private val t = MandatesTable

    override suspend fun save(mandate: Mandate): Mandate = dbQuery {
        t.insert {
            it[id] = mandate.id
            it[userId] = mandate.userId
            write(it, mandate)
            it[createdAt] = mandate.createdAt
            it[updatedAt] = mandate.updatedAt
        }
        mandate
    }

    override suspend fun update(mandate: Mandate): Mandate = dbQuery {
        val now = Instant.now()
        t.update({ t.id eq mandate.id }) {
            write(it, mandate)
            it[updatedAt] = now
        }
        mandate.copy(updatedAt = now)
    }

    override suspend fun findByUserId(userId: UUID): List<Mandate> = dbQuery {
        t.selectAll().where { t.userId eq userId }.orderBy(t.createdAt to SortOrder.ASC).map { it.toMandate() }
    }

    override suspend fun findByUmn(userId: UUID, umn: String): Mandate? = dbQuery {
        t.selectAll().where { (t.userId eq userId) and (t.umn.lowerCase() eq umn.lowercase()) }.singleOrNull()?.toMandate()
    }

    private fun write(it: UpdateBuilder<*>, m: Mandate) {
        it[t.rail] = m.rail.name.lowercase()
        it[t.payee] = m.payee.take(100)
        it[t.umn] = m.umn
        it[t.amount] = m.amount
        it[t.frequency] = m.frequency.name.lowercase()
        it[t.status] = m.status.name.lowercase()
        it[t.statusChangedAt] = m.statusChangedAt
        it[t.createdOn] = m.createdOn
        it[t.validUntil] = m.validUntil
        it[t.nextDebitOn] = m.nextDebitOn
        it[t.nextDebitAmount] = m.nextDebitAmount
        it[t.lastDebitAt] = m.lastDebitAt
        it[t.lastDebitAmount] = m.lastDebitAmount
        it[t.lastDebitTxnId] = m.lastDebitTxnId
        it[t.bank] = m.bank
        it[t.lastMessageRef] = m.lastMessageRef
    }

    private fun ResultRow.toMandate() = Mandate(
        id = this[t.id], userId = this[t.userId],
        rail = MandateRail.valueOf(this[t.rail].uppercase()),
        payee = this[t.payee], umn = this[t.umn], amount = this[t.amount],
        frequency = MandateFrequency.valueOf(this[t.frequency].uppercase()),
        status = MandateStatus.valueOf(this[t.status].uppercase()),
        statusChangedAt = this[t.statusChangedAt], createdOn = this[t.createdOn], validUntil = this[t.validUntil],
        nextDebitOn = this[t.nextDebitOn], nextDebitAmount = this[t.nextDebitAmount],
        lastDebitAt = this[t.lastDebitAt], lastDebitAmount = this[t.lastDebitAmount], lastDebitTxnId = this[t.lastDebitTxnId],
        bank = this[t.bank], lastMessageRef = this[t.lastMessageRef],
        createdAt = this[t.createdAt], updatedAt = this[t.updatedAt]
    )
}
