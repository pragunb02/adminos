package dev.adminos.api.domain.ingestion.pipeline

import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.ingestion.events.*
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IngestProcessorTest {

    private val events = InMemoryIngestEventRepository()
    private val transactions = InMemoryTransactionRepository()
    private val writer = RepositoryOutcomeWriter(transactions, events, dev.adminos.api.domain.financial.links.InMemoryTransactionLinkRepository())
    private val userId = UUID.randomUUID()

    private fun pipeline(vararg extra: IngestStep) =
        IngestPipeline(listOf(ToTransactionStep(), NormalizeMerchantStep(MerchantNormalizer()), CategorizeStep(CategorizationService())) + extra)

    private fun processor(p: IngestPipeline = pipeline(), clock: Clock = Clock.systemUTC()) = IngestProcessor(events, p, writer, clock)

    private fun event(ref: String = UUID.randomUUID().toString(), merchant: String = "SWIGGY*ORDER", direction: IngestDirection = IngestDirection.DEBIT) =
        IngestEvent(userId = userId, source = IngestSource.SMS, sourceRef = ref, occurredAt = Instant.parse("2026-10-01T03:42:00Z"),
            direction = direction, amount = BigDecimal("249.50"), merchantRaw = merchant, accountHint = "1234", paymentMethod = PaymentMethod.UPI)

    private suspend fun statuses() = events.findByUser(userId, 100).associate { it.sourceRef to it.status }

    @Test
    fun `pending events become categorised transactions linked to their event`() = runBlocking<Unit> {
        val e = event(ref = "a")
        events.saveNew(listOf(e))
        assertEquals(1, processor().processBatch())

        val txn = transactions.findByIngestEventId(e.id)!!
        assertEquals(TransactionType.DEBIT, txn.type)
        assertEquals(0, BigDecimal("249.50").compareTo(txn.amount))
        assertEquals("Swiggy", txn.merchantName)
        assertEquals(TransactionCategory.FOOD, txn.category)
        assertEquals("sms", txn.sourceType)
        assertEquals(IngestEventStatus.PROCESSED, statuses()["a"])
    }

    @Test
    fun `credits stay credits`() = runBlocking<Unit> {
        val e = event(merchant = "SALARY ACME", direction = IngestDirection.CREDIT)
        events.saveNew(listOf(e)); processor().processBatch()
        assertEquals(TransactionType.CREDIT, transactions.findByIngestEventId(e.id)!!.type)
    }

    @Test
    fun `an event is turned into a transaction exactly once`() = runBlocking<Unit> {
        val e = event()
        events.saveNew(listOf(e))
        val p = processor()
        p.processBatch(); p.processBatch()
        // Simulate a crash after the insert but before the status update, then a re-claim:
        writer.processed(e, transactions.findByIngestEventId(e.id)!!)
        assertEquals(1, transactions.findByUserId(userId, limit = 100).size)
    }

    @Test
    fun `a failing step marks only that event failed and the batch continues`() = runBlocking<Unit> {
        events.saveNew(listOf(event(ref = "ok-1"), event(ref = "boom"), event(ref = "ok-2")))
        val explode = IngestStep { ctx -> if (ctx.event.sourceRef == "boom") error("parser exploded") else StepResult.Continue(ctx) }
        processor(pipeline(explode)).processBatch()
        val s = statuses()
        assertEquals(IngestEventStatus.FAILED, s["boom"])
        assertEquals(IngestEventStatus.PROCESSED, s["ok-1"])
        assertEquals(IngestEventStatus.PROCESSED, s["ok-2"])
        assertTrue(events.findByUser(userId).first { it.sourceRef == "boom" }.error!!.contains("parser exploded"))
    }

    @Test
    fun `a dropping step marks the event ignored with its reason`() = runBlocking<Unit> {
        events.saveNew(listOf(event(ref = "dup")))
        processor(pipeline(IngestStep { StepResult.Drop("duplicate of statement row") })).processBatch()
        val e = events.findByUser(userId).single()
        assertEquals(IngestEventStatus.IGNORED, e.status)
        assertEquals("duplicate of statement row", e.error)
        assertEquals(0, transactions.findByUserId(userId, limit = 10).size)
    }

    @Test
    fun `claims abandoned by a crashed processor are retried after the timeout`() = runBlocking<Unit> {
        events.saveNew(listOf(event(ref = "stuck")))
        events.claim(10, staleBefore = Instant.EPOCH) // claimed, then the "processor" dies
        assertEquals(0, processor().processBatch(), "fresh claims are left alone")

        val later = Clock.fixed(Instant.now().plusSeconds(6 * 60), ZoneOffset.UTC)
        assertEquals(1, processor(clock = later).processBatch())
        assertEquals(IngestEventStatus.PROCESSED, statuses()["stuck"])
    }

    @Test
    fun `the background loop processes new events promptly after wake`() = runBlocking<Unit> {
        val p = processor()
        val job = p.start(this, sweepEvery = 60_000.milliseconds)
        val service = IngestEventService(events, onAccepted = p::wake)
        service.ingest(listOf(event(ref = "live")))
        withTimeout(2_000) { while (statuses()["live"] != IngestEventStatus.PROCESSED) delay(10) }
        job.cancelAndJoin()
    }
}
