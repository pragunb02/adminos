package dev.adminos.api.domain.financial.links

import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionRepository
import dev.adminos.api.domain.ingestion.pipeline.applyRelation
import dev.adminos.api.domain.ingestion.pipeline.asTransfer
import dev.adminos.api.domain.ingestion.pipeline.enrich
import java.util.UUID

/** A suggested relation: [related] may be a duplicate of / transfer with / refund of / reversal of [primary]. */
data class Suggestion(val link: TransactionLink, val primary: Transaction, val related: Transaction)

/**
 * The user's side of the accuracy engine (AOS-207/208/209): review suggested relations.
 * - duplicate → keep [Suggestion.primary] (blanks filled), remove the duplicate; its source stays linked
 * - transfer  → both sides become transfers (neither spending nor income)
 * - refund    → the credit becomes a refund of the debit's category; reversal → both become reversals
 * Reject is remembered for every kind.
 */
class SuggestionService(
    private val links: TransactionLinkRepository,
    private val transactions: TransactionRepository,
    private val unitOfWork: suspend (suspend () -> Unit) -> Unit = { it() }
) {
    suspend fun suggestions(userId: UUID, relation: LinkRelation? = null): List<Suggestion> =
        links.findByUser(userId, LinkKind.SUGGESTED, relation).mapNotNull { l ->
            val primary = transactions.findById(l.transactionId) ?: return@mapNotNull null
            val related = l.relatedTransactionId?.let { transactions.findById(it) } ?: return@mapNotNull null
            Suggestion(l, primary, related)
        }

    /** @return false if the suggestion doesn't exist, isn't the user's, or was already decided */
    suspend fun confirm(userId: UUID, linkId: UUID): Boolean {
        val link = pending(userId, linkId) ?: return false
        var done = false
        unitOfWork {
            val primary = transactions.findById(link.transactionId)
            val related = link.relatedTransactionId?.let { transactions.findById(it) }
            if (primary != null && related != null) {
                when (link.relation) {
                    LinkRelation.DUPLICATE -> {
                        transactions.update(enrich(primary, related))
                        links.update(link.copy(kind = LinkKind.MERGED, relatedTransactionId = null,
                            ingestEventId = related.ingestEventId ?: link.ingestEventId, reason = "${link.reason}; confirmed by user"))
                        transactions.delete(related.id)
                    }
                    LinkRelation.TRANSFER -> {
                        transactions.update(asTransfer(primary, "self_transfer"))
                        transactions.update(asTransfer(related, "self_transfer"))
                        links.update(link.copy(kind = LinkKind.MERGED, reason = "${link.reason}; confirmed by user"))
                    }
                    LinkRelation.REFUND, LinkRelation.REVERSAL -> applyRefundOrReversal(link, primary, related)
                }
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

    private suspend fun applyRefundOrReversal(link: TransactionLink, primary: Transaction, related: Transaction) {
        val (debit, credit) = if (primary.type == dev.adminos.api.domain.financial.TransactionType.DEBIT) primary to related else related to primary
        val (d, c) = applyRelation(link.relation, debit, credit)
        transactions.update(d)
        transactions.update(c)
        links.update(link.copy(kind = LinkKind.MERGED, reason = "${link.reason}; confirmed by user"))
    }

    private suspend fun pending(userId: UUID, linkId: UUID) =
        links.findById(linkId)?.takeIf { it.userId == userId && it.kind == LinkKind.SUGGESTED }
}
