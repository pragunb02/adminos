package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.*
import dev.adminos.api.domain.common.CursorCodec
import dev.adminos.api.infrastructure.database.tables.BillsTable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import dev.adminos.api.infrastructure.database.JsonColumns
import dev.adminos.api.infrastructure.database.dbQuery
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class ExposedBillRepository : BillRepository {

    override suspend fun save(bill: Bill): Bill = dbQuery {
        BillsTable.insert {
            it[id] = bill.id
            it[userId] = bill.userId
            it[billType] = bill.billType.name.lowercase()
            it[billerName] = bill.billerName
            it[accountRef] = bill.accountRef
            it[amount] = bill.amount
            it[minimumDue] = bill.minimumDue
            it[currency] = bill.currency
            it[dueDate] = bill.dueDate
            it[billingPeriodStart] = bill.billingPeriodStart
            it[billingPeriodEnd] = bill.billingPeriodEnd
            it[status] = bill.status.name.lowercase()
            it[paidAt] = bill.paidAt
            it[paidAmount] = bill.paidAmount
            it[paymentTxnId] = bill.paymentTxnId
            it[detectionSource] = bill.detectionSource
            it[sourceRef] = bill.sourceRef
            it[reminderSent3d] = bill.reminderSent3d
            it[reminderSent1d] = bill.reminderSent1d
            it[reminderDaysSent] = encodeDays(bill.sentReminderDays)
            it[metadata] = bill.metadata?.let { m -> JsonColumns.encode(m) }
            it[createdAt] = bill.createdAt
            it[updatedAt] = bill.updatedAt
        }
        bill
    }

    override suspend fun findById(id: UUID): Bill? = dbQuery {
        BillsTable.selectAll().where { BillsTable.id eq id }.singleOrNull()?.toBill()
    }

    override suspend fun findByUserId(
        userId: UUID, status: BillStatus?, from: LocalDate?, to: LocalDate?,
        cursor: String?, limit: Int
    ): List<Bill> = dbQuery {
        val query = BillsTable.selectAll().where { BillsTable.userId eq userId }
        status?.let { s -> query.andWhere { BillsTable.status eq s.name.lowercase() } }
        from?.let { f -> query.andWhere { BillsTable.dueDate greaterEq f } }
        to?.let { t -> query.andWhere { BillsTable.dueDate lessEq t } }
        cursor?.let { c ->
            val (key, _) = CursorCodec.decode(c)
            val (cursorDate, cursorId) = dev.adminos.api.domain.financial.parseBillCursor(key)
            query.andWhere {
                if (cursorId == null) BillsTable.dueDate greater cursorDate
                else (BillsTable.dueDate greater cursorDate) or ((BillsTable.dueDate eq cursorDate) and (BillsTable.id greater cursorId))
            }
        }
        query.orderBy(BillsTable.dueDate to SortOrder.ASC, BillsTable.id to SortOrder.ASC)
            .limit(limit)
            .map { it.toBill() }
    }

    override suspend fun countByUserId(userId: UUID, status: BillStatus?): Long = dbQuery {
        val query = BillsTable.selectAll().where { BillsTable.userId eq userId }
        status?.let { s -> query.andWhere { BillsTable.status eq s.name.lowercase() } }
        query.count()
    }

    override suspend fun findPendingBills(userId: UUID): List<Bill> = dbQuery {
        BillsTable.selectAll().where {
            (BillsTable.userId eq userId) and
                (BillsTable.status inList listOf("upcoming", "due", "overdue"))
        }.orderBy(BillsTable.dueDate, SortOrder.ASC).map { it.toBill() }
    }

    override suspend fun findUpcoming(userId: UUID, today: LocalDate, withinDays: Int): List<Bill> = dbQuery {
        val cutoff = today.plusDays(withinDays.toLong())
        BillsTable.selectAll().where {
            (BillsTable.userId eq userId) and
                (BillsTable.status inList listOf("upcoming", "due")) and
                (BillsTable.dueDate greaterEq today) and
                (BillsTable.dueDate lessEq cutoff)
        }.orderBy(BillsTable.dueDate, SortOrder.ASC).map { it.toBill() }
    }

    override suspend fun findUpcoming(today: LocalDate, withinDays: Int): List<Bill> = dbQuery {
        val cutoff = today.plusDays(withinDays.toLong())
        BillsTable.selectAll().where {
            (BillsTable.status inList listOf("upcoming", "due")) and
                (BillsTable.dueDate greaterEq today) and
                (BillsTable.dueDate lessEq cutoff)
        }.map { it.toBill() }
    }

    override suspend fun findOverdue(today: LocalDate): List<Bill> = dbQuery {
        BillsTable.selectAll().where {
            (BillsTable.status inList listOf("upcoming", "due")) and
                (BillsTable.dueDate less today)
        }.map { it.toBill() }
    }

    override suspend fun update(bill: Bill): Bill = dbQuery {
        val now = Instant.now()
        BillsTable.update({ BillsTable.id eq bill.id }) {
            it[billType] = bill.billType.name.lowercase()
            it[billerName] = bill.billerName
            it[amount] = bill.amount
            it[minimumDue] = bill.minimumDue
            it[dueDate] = bill.dueDate
            it[status] = bill.status.name.lowercase()
            it[paidAt] = bill.paidAt
            it[paidAmount] = bill.paidAmount
            it[paymentTxnId] = bill.paymentTxnId
            it[reminderSent3d] = bill.reminderSent3d
            it[reminderSent1d] = bill.reminderSent1d
            it[reminderDaysSent] = encodeDays(bill.sentReminderDays)
            // AOS-508: metadata used to be dropped on update (the in-memory repository kept it).
            it[metadata] = bill.metadata?.let { m -> JsonColumns.encode(m) }
            it[updatedAt] = now
        }
        bill.copy(updatedAt = now)
    }

    @Suppress("UNCHECKED_CAST")
    private fun ResultRow.toBill() = Bill(
        id = this[BillsTable.id],
        userId = this[BillsTable.userId],
        billType = BillType.valueOf(this[BillsTable.billType].uppercase()),
        billerName = this[BillsTable.billerName],
        accountRef = this[BillsTable.accountRef],
        amount = this[BillsTable.amount],
        minimumDue = this[BillsTable.minimumDue],
        currency = this[BillsTable.currency],
        dueDate = this[BillsTable.dueDate],
        billingPeriodStart = this[BillsTable.billingPeriodStart],
        billingPeriodEnd = this[BillsTable.billingPeriodEnd],
        status = BillStatus.valueOf(this[BillsTable.status].uppercase()),
        paidAt = this[BillsTable.paidAt],
        paidAmount = this[BillsTable.paidAmount],
        paymentTxnId = this[BillsTable.paymentTxnId],
        detectionSource = this[BillsTable.detectionSource],
        sourceRef = this[BillsTable.sourceRef],
        reminderSent3d = this[BillsTable.reminderSent3d],
        reminderSent1d = this[BillsTable.reminderSent1d],
        sentReminderDays = decodeDays(this[BillsTable.reminderDaysSent]),
        metadata = this[BillsTable.metadata]?.let {
            JsonColumns.decode(it)
        },
        createdAt = this[BillsTable.createdAt],
        updatedAt = this[BillsTable.updatedAt]
    )
}
