package dev.adminos.api.domain.ingestion.pipeline

import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionRepository
import dev.adminos.api.domain.ingestion.events.IngestEvent
import dev.adminos.api.domain.ingestion.events.IngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestEventStatus
import dev.adminos.api.domain.financial.links.LinkKind
import dev.adminos.api.domain.financial.links.TransactionLink
import dev.adminos.api.domain.financial.links.TransactionLinkRepository
import dev.adminos.api.domain.financial.links.TransactionMatcher

/**
 * Writes outcomes through the repositories. [unitOfWork] wraps each outcome so the transaction
 * insert and the status update commit together (Exposed: one DB transaction; in-memory: direct).
 * Idempotent: a re-claimed event whose transaction already exists is just marked processed.
 */
class RepositoryOutcomeWriter(
    private val transactions: TransactionRepository,
    private val events: IngestEventRepository,
    private val links: TransactionLinkRepository,
    private val unitOfWork: suspend (suspend () -> Unit) -> Unit = { it() }
) : IngestOutcomeWriter {

    override suspend fun processed(event: IngestEvent, transaction: Transaction, suggestion: MatchSuggestion?) = unitOfWork {
        if (transactions.findByIngestEventId(event.id) == null) {
            transactions.save(transaction)
            suggestion?.let {
                links.save(TransactionLink(
                    userId = transaction.userId, transactionId = it.target.id, relatedTransactionId = transaction.id,
                    ingestEventId = event.id, source = transaction.sourceType, kind = LinkKind.SUGGESTED,
                    score = it.score, reason = it.reason, rulesVersion = TransactionMatcher.RULES_VERSION
                ))
            }
        }
        events.finish(event.id, IngestEventStatus.PROCESSED)
    }

    override suspend fun merged(event: IngestEvent, target: Transaction, score: Int, reason: String) = unitOfWork {
        transactions.update(target)
        links.save(TransactionLink(
            userId = target.userId, transactionId = target.id, ingestEventId = event.id,
            source = event.source.name.lowercase(), kind = LinkKind.MERGED,
            score = score, reason = reason, rulesVersion = TransactionMatcher.RULES_VERSION
        ))
        events.finish(event.id, IngestEventStatus.PROCESSED, "merged into ${target.id}")
    }

    override suspend fun ignored(event: IngestEvent, reason: String) = unitOfWork {
        events.finish(event.id, IngestEventStatus.IGNORED, reason)
    }

    override suspend fun failed(event: IngestEvent, error: String) = unitOfWork {
        events.finish(event.id, IngestEventStatus.FAILED, error)
    }
}
