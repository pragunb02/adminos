package dev.adminos.api.domain.ingestion.pipeline

import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.ingestion.events.*
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Listeners hear about each stored transaction, after the commit (AOS-505). */
class TransactionListenersTest {
    private val events = InMemoryIngestEventRepository()
    private val transactions = InMemoryTransactionRepository()
    private val writer = RepositoryOutcomeWriter(transactions, events, dev.adminos.api.domain.financial.links.InMemoryTransactionLinkRepository())
    private val userId = UUID.randomUUID()
    private val pipeline = IngestPipeline(listOf(ToTransactionStep(), NormalizeMerchantStep(MerchantNormalizer()), CategorizeStep(CategorizationService())))
    private val heard = mutableListOf<Transaction>()

    private fun event(ref: String = UUID.randomUUID().toString(), amount: String = "249.50") =
        IngestEvent(userId = userId, source = IngestSource.SMS, sourceRef = ref, occurredAt = Instant.parse("2026-10-01T03:42:00Z"),
            direction = IngestDirection.DEBIT, amount = BigDecimal(amount), merchantRaw = "SWIGGY*ORDER", accountHint = "1234", paymentMethod = PaymentMethod.UPI)

    @Test
    fun `each new transaction is passed to every listener, as stored`() = runBlocking<Unit> {
        val second = mutableListOf<UUID>()
        val p = IngestProcessor(events, pipeline, writer, listeners = listOf(TransactionCreatedListener { heard += it }, TransactionCreatedListener { second += it.id }))
        val e = event()
        events.saveNew(listOf(e))
        p.processBatch()
        val stored = transactions.findByIngestEventId(e.id)!!
        assertEquals(listOf(stored), heard)
        assertEquals(listOf(stored.id), second)
    }

    @Test
    fun `a failing listener is logged and never fails the event or the other listeners`() = runBlocking<Unit> {
        val p = IngestProcessor(events, pipeline, writer, listeners = listOf(
            TransactionCreatedListener { error("anomaly store down") },
            TransactionCreatedListener { heard += it }
        ))
        val e = event()
        events.saveNew(listOf(e))
        p.processBatch()
        assertEquals(IngestEventStatus.PROCESSED, events.findByUser(userId, 10).single().status)
        assertEquals(1, heard.size)
    }

    @Test
    fun `a re-processed event hands listeners the transaction stored the first time`() = runBlocking<Unit> {
        val e = event()
        val first = writer.processed(e, pipelineOutcome(e))
        val again = writer.processed(e, pipelineOutcome(e))
        assertEquals(first.id, again.id)
        assertEquals(1, transactions.findByUserId(userId).size)
    }

    @Test
    fun `ignored events reach no listener`() = runBlocking<Unit> {
        val zero = IngestPipeline(listOf(ToTransactionStep(), IngestStep { StepResult.Drop("not a transaction") }))
        val p = IngestProcessor(events, zero, writer, listeners = listOf(TransactionCreatedListener { heard += it }))
        events.saveNew(listOf(event()))
        p.processBatch()
        assertTrue(heard.isEmpty())
    }

    private suspend fun pipelineOutcome(e: IngestEvent): Transaction =
        (pipeline.run(e) as PipelineOutcome.Created).transaction
}
