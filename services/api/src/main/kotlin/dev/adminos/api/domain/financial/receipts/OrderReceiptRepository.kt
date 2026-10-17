package dev.adminos.api.domain.financial.receipts

import dev.adminos.api.domain.common.TimeRange
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Order receipts (AOS-903). In production the Gmail worker writes them (V50); the API reads them
 * and records how each is tied to its payment.
 */
interface OrderReceiptRepository {
    /** Stores a receipt as is (tests and dev mode; the worker merges emails itself). */
    suspend fun save(receipt: OrderReceipt): OrderReceipt
    suspend fun findById(id: UUID): OrderReceipt?
    /** A user's receipts ordered in [range], oldest first. */
    suspend fun findByUser(userId: UUID, range: TimeRange): List<OrderReceipt>
    /** Unmatched receipts of every user ordered since [since] that have an amount to match on, oldest first. */
    suspend fun findUnmatched(since: Instant, limit: Int): List<OrderReceipt>
    /** A user's unmatched receipts ordered in [range]. */
    suspend fun findUnmatchedForUser(userId: UUID, range: TimeRange): List<OrderReceipt>
    /** Which of [transactionIds] are already the matched payment of a receipt. */
    suspend fun matchedTransactionIds(transactionIds: Collection<UUID>): Set<UUID>
    /** Records [match] and the rejected payments; returns the updated receipt. */
    suspend fun updateMatch(id: UUID, match: ReceiptMatch, rejected: Set<UUID>): OrderReceipt
}

class InMemoryOrderReceiptRepository : OrderReceiptRepository {
    private val store = ConcurrentHashMap<UUID, OrderReceipt>()

    override suspend fun save(receipt: OrderReceipt): OrderReceipt {
        store[receipt.id] = receipt
        return receipt
    }

    override suspend fun findById(id: UUID): OrderReceipt? = store[id]

    override suspend fun findByUser(userId: UUID, range: TimeRange): List<OrderReceipt> =
        store.values.filter { it.userId == userId && it.orderedAt in range }.sortedWith(compareBy({ it.orderedAt }, { it.id }))

    override suspend fun findUnmatched(since: Instant, limit: Int): List<OrderReceipt> =
        store.values.filter { it.match.state == ReceiptMatchState.UNMATCHED && it.orderedAt >= since && (it.orderTotal != null || it.paidAmount != null) }
            .sortedWith(compareBy({ it.orderedAt }, { it.id })).take(limit)

    override suspend fun findUnmatchedForUser(userId: UUID, range: TimeRange): List<OrderReceipt> =
        findByUser(userId, range).filter { it.match.state == ReceiptMatchState.UNMATCHED }

    override suspend fun matchedTransactionIds(transactionIds: Collection<UUID>): Set<UUID> =
        store.values.filter { it.match.state == ReceiptMatchState.MATCHED }.mapNotNull { it.match.transactionId }.filter { it in transactionIds }.toSet()

    override suspend fun updateMatch(id: UUID, match: ReceiptMatch, rejected: Set<UUID>): OrderReceipt {
        val current = store[id] ?: throw NoSuchElementException("order receipt $id not found")
        if (match.state == ReceiptMatchState.MATCHED) {
            val taken = store.values.any { it.id != id && it.match.state == ReceiptMatchState.MATCHED && it.match.transactionId == match.transactionId }
            check(!taken) { "payment ${match.transactionId} is already another order's" } // the V50 unique index
        }
        val updated = current.copy(match = match, rejectedTransactionIds = rejected, updatedAt = Instant.now())
        store[id] = updated
        return updated
    }
}
