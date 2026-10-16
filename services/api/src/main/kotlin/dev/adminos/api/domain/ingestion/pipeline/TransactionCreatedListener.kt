package dev.adminos.api.domain.ingestion.pipeline

import dev.adminos.api.domain.financial.Transaction

/**
 * Called after the pipeline has stored a transaction (Observer, AOS-505): anomaly alerts now, bill
 * matching next (AOS-508). A new reaction is one class registered in Koin as a listener.
 *
 * Runs after the commit and may run again for the same transaction (a re-claimed event), so
 * listeners must be idempotent. A listener's failure is logged and never fails the event.
 */
fun interface TransactionCreatedListener {
    suspend fun onCreated(transaction: Transaction)
}
