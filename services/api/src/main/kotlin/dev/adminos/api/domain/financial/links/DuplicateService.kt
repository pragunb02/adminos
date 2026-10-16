package dev.adminos.api.domain.financial.links

import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionRepository
import dev.adminos.api.domain.ingestion.pipeline.enrich
import java.util.UUID

/** A suggested duplicate: [duplicate] may be the same payment as [kept]. */
data class DuplicateSuggestion(val link: TransactionLink, val kept: Transaction, val duplicate: Transaction)

/**
 * The user's side of AOS-207: review suggested duplicates. Confirm keeps the older transaction (with its
 * blanks filled) and removes the duplicate; its source event stays linked, so nothing is lost. Reject is remembered.
 */
class DuplicateService(
    private val links: TransactionLinkRepository,
    private val transactions: TransactionRepository,
    private val unitOfWork: suspend (suspend () -> Unit) -> Unit = { it() }
) {
    suspend fun suggestions(userId: UUID): List<DuplicateSuggestion> =
        links.findByUser(userId, LinkKind.SUGGESTED).mapNotNull { l ->
            val kept = transactions.findById(l.transactionId) ?: return@mapNotNull null
            val dup = l.relatedTransactionId?.let { transactions.findById(it) } ?: return@mapNotNull null
            DuplicateSuggestion(l, kept, dup)
        }

    /** @return false if the suggestion doesn't exist, isn't the user's, or was already decided */
    suspend fun confirm(userId: UUID, linkId: UUID): Boolean {
        val link = pending(userId, linkId) ?: return false
        var done = false
        unitOfWork {
            val kept = transactions.findById(link.transactionId)
            val dup = link.relatedTransactionId?.let { transactions.findById(it) }
            if (kept != null && dup != null) {
                transactions.update(enrich(kept, dup))
                links.update(link.copy(kind = LinkKind.MERGED, relatedTransactionId = null, ingestEventId = dup.ingestEventId ?: link.ingestEventId,
                    reason = "${link.reason}; confirmed by user"))
                transactions.delete(dup.id)
                done = true
            }
        }
        return done
    }

    suspend fun reject(userId: UUID, linkId: UUID): Boolean {
        val link = pending(userId, linkId) ?: return false
        links.update(link.copy(kind = LinkKind.REJECTED, reason = "${link.reason}; rejected by user"))
        return true
    }

    private suspend fun pending(userId: UUID, linkId: UUID) =
        links.findById(linkId)?.takeIf { it.userId == userId && it.kind == LinkKind.SUGGESTED }
}
