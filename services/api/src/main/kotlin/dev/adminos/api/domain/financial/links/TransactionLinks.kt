package dev.adminos.api.domain.financial.links

import java.time.Instant
import java.util.UUID

enum class LinkKind { MERGED, SUGGESTED, REJECTED }

/**
 * How a record from another source relates to [transactionId] (V13). Source rows stay immutable:
 * a merge is a link, so it can be audited and undone.
 */
data class TransactionLink(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val transactionId: UUID,
    val relatedTransactionId: UUID? = null,
    val ingestEventId: UUID? = null,
    val source: String,
    val kind: LinkKind,
    val score: Int,
    val reason: String,
    val rulesVersion: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

interface TransactionLinkRepository {
    suspend fun save(link: TransactionLink): TransactionLink
    suspend fun findById(id: UUID): TransactionLink?
    suspend fun findByUser(userId: UUID, kind: LinkKind): List<TransactionLink>
    /** Sources already merged into each transaction (besides its own). */
    suspend fun mergedSources(transactionIds: Collection<UUID>): Map<UUID, Set<String>>
    suspend fun update(link: TransactionLink): TransactionLink
}

class InMemoryTransactionLinkRepository : TransactionLinkRepository {
    private val store = java.util.concurrent.ConcurrentHashMap<UUID, TransactionLink>()
    override suspend fun save(link: TransactionLink) = link.also { store[it.id] = it }
    override suspend fun findById(id: UUID) = store[id]
    override suspend fun findByUser(userId: UUID, kind: LinkKind) =
        store.values.filter { it.userId == userId && it.kind == kind }.sortedByDescending { it.createdAt }
    override suspend fun mergedSources(transactionIds: Collection<UUID>): Map<UUID, Set<String>> =
        store.values.filter { it.kind == LinkKind.MERGED && it.transactionId in transactionIds }
            .groupBy { it.transactionId }.mapValues { (_, ls) -> ls.map { it.source }.toSet() }
    override suspend fun update(link: TransactionLink) = link.copy(updatedAt = Instant.now()).also { store[it.id] = it }
}
