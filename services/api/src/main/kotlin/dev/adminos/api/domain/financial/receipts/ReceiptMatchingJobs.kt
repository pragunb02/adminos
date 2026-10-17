package dev.adminos.api.domain.financial.receipts

import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.ingestion.pipeline.TransactionCreatedListener
import dev.adminos.api.domain.scheduling.JobName
import dev.adminos.api.domain.scheduling.JobResult
import dev.adminos.api.domain.scheduling.Schedule
import dev.adminos.api.domain.scheduling.ScheduledJob
import java.time.Duration

/** After each stored payment: does it pay an order whose receipt is already here? (AOS-903) */
class ReceiptPaymentListener(private val matching: ReceiptMatchingService) : TransactionCreatedListener {
    override suspend fun onCreated(transaction: Transaction) {
        matching.onPayment(transaction)
    }
}

/**
 * Every 10 minutes: receipts the Gmail worker stored after their payment are matched (AOS-903).
 * Idempotent: only unmatched receipts are looked at, and a decided one is never changed by a rerun.
 */
class ReceiptMatchingJob(private val matching: ReceiptMatchingService) : ScheduledJob {
    override val name = JobName("receipt-matching")
    override val schedule = Schedule.Every(Duration.ofMinutes(10))

    override suspend fun run(): JobResult {
        val c = matching.matchPending()
        return JobResult.of("checked" to c.checked, "matched" to c.matched, "suggested" to c.suggested, "noPayment" to c.noPayment)
    }
}
