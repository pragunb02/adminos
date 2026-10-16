package dev.adminos.api.domain.ingestion.pipeline

import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionRepository
import dev.adminos.api.domain.ingestion.events.IngestEvent
import dev.adminos.api.domain.ingestion.events.IngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestEventStatus

/**
 * Writes outcomes through the repositories. [unitOfWork] wraps each outcome so the transaction
 * insert and the status update commit together (Exposed: one DB transaction; in-memory: direct).
 * Idempotent: a re-claimed event whose transaction already exists is just marked processed.
 */
class RepositoryOutcomeWriter(
    private val transactions: TransactionRepository,
    private val events: IngestEventRepository,
    private val unitOfWork: suspend (suspend () -> Unit) -> Unit = { it() }
) : IngestOutcomeWriter {

    override suspend fun processed(event: IngestEvent, transaction: Transaction) = unitOfWork {
        if (transactions.findByIngestEventId(event.id) == null) transactions.save(transaction)
        events.finish(event.id, IngestEventStatus.PROCESSED)
    }

    override suspend fun ignored(event: IngestEvent, reason: String) = unitOfWork {
        events.finish(event.id, IngestEventStatus.IGNORED, reason)
    }

    override suspend fun failed(event: IngestEvent, error: String) = unitOfWork {
        events.finish(event.id, IngestEventStatus.FAILED, error)
    }
}
