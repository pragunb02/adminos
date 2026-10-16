package dev.adminos.api.domain.financial

import dev.adminos.api.domain.ingestion.pipeline.TransactionCreatedListener

/** After each stored transaction, mark the bill it pays as paid (AOS-508); see [BillPaymentMatcher]. */
class BillPaymentListener(private val billing: BillTrackingService) : TransactionCreatedListener {
    override suspend fun onCreated(transaction: Transaction) {
        billing.matchPayment(transaction)
    }
}
