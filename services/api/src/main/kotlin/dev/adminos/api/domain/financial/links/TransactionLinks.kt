package dev.adminos.api.domain.financial.links

import java.time.Instant
import java.util.UUID

/** State of a link: applied, awaiting the user, or rejected by the user. */
enum class LinkKind { MERGED, SUGGESTED, REJECTED }

/** What kind of pair a link describes (D-39). MERGED for a duplicate means "folded in"; for the others "linked". */
enum class LinkRelation { DUPLICATE, TRANSFER, REFUND, REVERSAL }

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
    val relation: LinkRelation = LinkRelation.DUPLICATE,
    val score: Int,
    val reason: String,
    val rulesVersion: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

interface TransactionLinkRepository {
    suspend fun save(link: TransactionLink): TransactionLink
    suspend fun findById(id: UUID): TransactionLink?
    suspend fun findByUser(userId: UUID, kind: LinkKind, relation: LinkRelation? = null): List<TransactionLink>
    /** All links (any state) touching a transaction, as either side. */
    suspend fun findByTransaction(transactionId: UUID): List<TransactionLink>
    /** Sources already merged into each transaction (besides its own). */
    suspend fun mergedSources(transactionIds: Collection<UUID>): Map<UUID, Set<String>>
    suspend fun update(link: TransactionLink): TransactionLink
}

class InMemoryTransactionLinkRepository : TransactionLinkRepository {
    private val store = java.util.concurrent.ConcurrentHashMap<UUID, TransactionLink>()
    override suspend fun save(link: TransactionLink) = link.also { store[it.id] = it }
    override suspend fun findById(id: UUID) = store[id]
    override suspend fun findByUser(userId: UUID, kind: LinkKind, relation: LinkRelation?) =
        store.values.filter { it.userId == userId && it.kind == kind && (relation == null || it.relation == relation) }.sortedByDescending { it.createdAt }
    override suspend fun findByTransaction(transactionId: UUID) =
        store.values.filter { it.transactionId == transactionId || it.relatedTransactionId == transactionId }
    override suspend fun mergedSources(transactionIds: Collection<UUID>): Map<UUID, Set<String>> =
        store.values.filter { it.kind == LinkKind.MERGED && it.relation == LinkRelation.DUPLICATE && it.transactionId in transactionIds }
            .groupBy { it.transactionId }.mapValues { (_, ls) -> ls.map { it.source }.toSet() }
    override suspend fun update(link: TransactionLink) = link.copy(updatedAt = Instant.now()).also { store[it.id] = it }
}
