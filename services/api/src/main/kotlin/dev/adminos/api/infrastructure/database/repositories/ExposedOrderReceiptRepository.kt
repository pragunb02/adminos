package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.common.TimeRange
import dev.adminos.api.domain.financial.receipts.*
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.OrderReceiptFeesTable as F
import dev.adminos.api.infrastructure.database.tables.OrderReceiptRefundsTable as R
import dev.adminos.api.infrastructure.database.tables.OrderReceiptsTable as T
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.time.Instant
import java.util.UUID

/** Postgres [OrderReceiptRepository] (AOS-903, V50). Enum values are mapped here, in one place; the V50 CHECKs keep them known. */
class ExposedOrderReceiptRepository : OrderReceiptRepository {
    override suspend fun save(receipt: OrderReceipt): OrderReceipt = dbQuery {
        T.insert {
            it[id] = receipt.id; it[userId] = receipt.userId; it[app] = receipt.app
            it[vertical] = receipt.vertical.name.lowercase(); it[orderRefHash] = receipt.orderRefHash; it[sourceRef] = receipt.sourceRef
            it[orderedAt] = receipt.orderedAt; it[status] = receipt.status.name.lowercase(); it[orderTotal] = receipt.orderTotal
            it[itemCount] = receipt.itemCount; it[paymentMode] = receipt.paymentMode.name.lowercase(); it[paidAmount] = receipt.paidAmount
            writeMatch(it, receipt.match, receipt.rejectedTransactionIds)
            it[createdAt] = receipt.createdAt; it[updatedAt] = receipt.updatedAt
        }
        receipt.fees.forEach { (kind, value) ->
            F.insert { it[receiptId] = receipt.id; it[F.kind] = kind.name.lowercase(); it[amount] = value }
        }
        receipt.refunds.distinctBy { it.stripTrailingZeros() }.forEach { value ->
            R.insert { it[receiptId] = receipt.id; it[amount] = value; it[sourceRef] = receipt.sourceRef; it[refundedAt] = receipt.orderedAt }
        }
        receipt
    }

    override suspend fun findById(id: UUID): OrderReceipt? = dbQuery {
        load(T.selectAll().where { T.id eq id }.toList()).singleOrNull()
    }

    override suspend fun findByUser(userId: UUID, range: TimeRange): List<OrderReceipt> = dbQuery {
        load(T.selectAll().where { (T.userId eq userId) and (T.orderedAt greaterEq range.from) and (T.orderedAt less range.until) }
            .orderBy(T.orderedAt to SortOrder.ASC, T.id to SortOrder.ASC).toList())
    }

    override suspend fun findUnmatched(since: Instant, limit: Int): List<OrderReceipt> = dbQuery {
        load(T.selectAll().where {
            // A linked receipt whose payment was deleted (FK set null) is unmatched again (readMatch).
            ((T.matchState eq UNMATCHED) or ((T.matchState inList listOf(MATCHED, SUGGESTED)) and T.transactionId.isNull())) and
                (T.orderedAt greaterEq since) and (T.orderTotal.isNotNull() or T.paidAmount.isNotNull())
        }.orderBy(T.orderedAt to SortOrder.ASC, T.id to SortOrder.ASC).limit(limit).toList())
    }

    override suspend fun findUnmatchedForUser(userId: UUID, range: TimeRange): List<OrderReceipt> =
        findByUser(userId, range).filter { it.match.state == ReceiptMatchState.UNMATCHED }

    override suspend fun matchedTransactionIds(transactionIds: Collection<UUID>): Set<UUID> = dbQuery {
        if (transactionIds.isEmpty()) emptySet()
        else T.select(T.transactionId).where { (T.matchState eq MATCHED) and (T.transactionId inList transactionIds) }
            .mapNotNull { it[T.transactionId] }.toSet()
    }

    override suspend fun updateMatch(id: UUID, match: ReceiptMatch, rejected: Set<UUID>): OrderReceipt = dbQuery {
        val now = Instant.now()
        val changed = T.update({ T.id eq id }) {
            writeMatch(it, match, rejected)
            it[updatedAt] = now
        }
        if (changed == 0) throw NoSuchElementException("order receipt $id not found")
        load(T.selectAll().where { T.id eq id }.toList()).single()
    }

    private fun writeMatch(it: org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>, match: ReceiptMatch, rejected: Set<UUID>) {
        it[T.matchState] = match.state.name.lowercase()
        it[T.transactionId] = match.transactionId
        it[T.matchScore] = match.score
        it[T.matchReason] = match.reason
        it[T.matchedBy] = match.matchedBy?.name?.lowercase()
        it[T.rejectedTransactionIds] = rejected.takeIf { r -> r.isNotEmpty() }?.sorted()?.joinToString(",")
    }

    /** Rows → receipts, with their fees and refunds read in two queries for the whole batch. */
    private fun load(rows: List<ResultRow>): List<OrderReceipt> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it[T.id] }
        val fees = F.selectAll().where { F.receiptId inList ids }.groupBy({ it[F.receiptId] }) { enumOf<FeeKind>(it[F.kind]) to it[F.amount] }
        val refunds = R.selectAll().where { R.receiptId inList ids }.groupBy({ it[R.receiptId] }) { it[R.amount] }
        return rows.map { row ->
            val id = row[T.id]
            OrderReceipt(
                id = id, userId = row[T.userId], app = row[T.app], vertical = enumOf(row[T.vertical]), orderRefHash = row[T.orderRefHash],
                sourceRef = row[T.sourceRef], orderedAt = row[T.orderedAt], status = enumOf(row[T.status]), orderTotal = row[T.orderTotal],
                itemCount = row[T.itemCount], paymentMode = enumOf(row[T.paymentMode]), paidAmount = row[T.paidAmount],
                fees = fees[id].orEmpty().toMap(),
                refunds = refunds[id].orEmpty(),
                match = readMatch(row), rejectedTransactionIds = row[T.rejectedTransactionIds].orEmpty().split(',').filter { it.isNotBlank() }.map(UUID::fromString).toSet(),
                createdAt = row[T.createdAt], updatedAt = row[T.updatedAt]
            )
        }
    }

    /** A matched or suggested receipt whose payment was deleted (FK set null) reads as unmatched again. */
    private fun readMatch(row: ResultRow): ReceiptMatch {
        val state = enumOf<ReceiptMatchState>(row[T.matchState])
        val txn = row[T.transactionId]
        val linked = state == ReceiptMatchState.MATCHED || state == ReceiptMatchState.SUGGESTED
        if (linked && txn == null) return ReceiptMatch.UNMATCHED
        return ReceiptMatch(state, txn, row[T.matchScore], row[T.matchReason], row[T.matchedBy]?.let { enumOf<MatchedBy>(it) })
    }

    private inline fun <reified E : Enum<E>> enumOf(raw: String): E = enumValues<E>().first { it.name.equals(raw, ignoreCase = true) }

    private companion object {
        const val UNMATCHED = "unmatched"
        const val MATCHED = "matched"
        const val SUGGESTED = "suggested"
    }
}
