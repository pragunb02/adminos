package dev.adminos.api.domain.ingestion.pipeline

import dev.adminos.api.InfraIngestTestKit.ist
import dev.adminos.api.InfraIngestTestKit.txn
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.financial.links.InMemoryTransactionLinkRepository
import dev.adminos.api.domain.financial.links.LinkKind
import dev.adminos.api.domain.financial.links.LinkRelation
import dev.adminos.api.domain.financial.links.RefundReversalDetector
import dev.adminos.api.domain.financial.links.TransactionLink
import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.events.IngestEvent
import dev.adminos.api.domain.ingestion.events.IngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestEventStatus
import dev.adminos.api.domain.ingestion.events.IngestSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** Pipeline orchestration, steps' edge cases, the normalizer and the background processor (AOS-312). */
class IngestionPipelineCoverageTest {

    private val user = UUID.randomUUID()
    private fun event(ref: String = "r-${UUID.randomUUID()}", direction: IngestDirection = IngestDirection.DEBIT, amount: String = "249.00") = IngestEvent(
        userId = user, source = IngestSource.SMS, sourceRef = ref, occurredAt = Instant.parse("2026-10-01T03:42:00Z"),
        direction = direction, amount = BigDecimal(amount), merchantRaw = "SWIGGY"
    )

    // ── Orchestration ──

    @Test
    fun `steps after the first can't read a transaction that was never built`() {
        assertFailsWith<IllegalStateException> { PipelineContext(event()).txn }
    }

    @Test
    fun `a pipeline must start with ToTransactionStep`() {
        assertFailsWith<IllegalArgumentException> { IngestPipeline(listOf(NormalizeMerchantStep(MerchantNormalizer()))) }
    }

    @Test
    fun `enrich fills every blank and never overwrites a value`() {
        val account = UUID.randomUUID()
        val blank = txn(user, TransactionType.DEBIT)
        val full = txn(user, TransactionType.DEBIT, merchant = "Swiggy", raw = "UPI-SWIGGY", last4 = "1234", vpa = "swiggy@icici",
            ref = "123456789012", method = PaymentMethod.UPI).copy(accountId = account)
        assertEquals(listOf("Swiggy", "UPI-SWIGGY", "1234", "swiggy@icici", "123456789012"),
            enrich(blank, full).let { listOf(it.merchantName, it.merchantRaw, it.accountLast4, it.upiVpa, it.paymentRef) })
        assertEquals(account, enrich(blank, full).accountId)
        assertEquals(full, enrich(full, blank))
    }

    // ── Normalizer ──

    private val normalizer = MerchantNormalizer()

    @Test
    fun `a UPI handle becomes a title-cased name when nothing else matches`() {
        assertEquals("Ramu Chai", normalizer.normalize(raw = null, upiVpa = "ramu.chai@ybl"))
    }

    @Test
    fun `a handle that is only digits is not a name`() {
        assertNull(normalizer.normalize(raw = null, upiVpa = "9876543210@ybl"))
    }

    @Test
    fun `a blank handle is ignored`() {
        assertNull(normalizer.normalize(raw = null, upiVpa = "@ybl"))
        assertNull(normalizer.normalize(raw = null, upiVpa = null))
    }

    @Test
    fun `a narration left with only one letter after the suffixes is not a name`() {
        assertNull(NarrationCleaner.clean("X INDIA"))
    }

    @Test
    fun `terminal ids with several letters are kept, card numbers are dropped`() {
        assertEquals("S1ANBG01 CAFE", NarrationCleaner.clean("POS X1234 S1ANBG01 CAFE"))
    }

    // ── Steps ──

    @Test
    fun `a transfer keeps a category the user chose`() {
        val chosen = txn(user, TransactionType.DEBIT).copy(category = TransactionCategory.FOOD, subcategory = "chai", categorySource = "user")
        val t = asTransfer(chosen, "cash")
        assertEquals(TransactionNature.TRANSFER, t.nature)
        assertEquals(TransactionCategory.FOOD, t.category); assertEquals("chai", t.subcategory)
    }

    @Test
    fun `a duplicate relation leaves both transactions as they are`() {
        val d = txn(user, TransactionType.DEBIT); val c = txn(user, TransactionType.CREDIT)
        assertEquals(d to c, applyRelation(LinkRelation.DUPLICATE, d, c))
    }

    @Test
    fun `a refund credit the user categorised keeps its category`() {
        val d = txn(user, TransactionType.DEBIT).copy(category = TransactionCategory.SHOPPING)
        val c = txn(user, TransactionType.CREDIT).copy(category = TransactionCategory.OTHER, categorySource = "user")
        val (_, credit) = applyRelation(LinkRelation.REFUND, d, c)
        assertEquals(TransactionCategory.OTHER, credit.category)
        assertEquals(TransactionNature.REFUND, credit.nature)
    }

    private val txns = InMemoryTransactionRepository()
    private val links = InMemoryTransactionLinkRepository()
    private val refundStep = RefundReversalStep(txns, links, RefundReversalDetector())

    @Test
    fun `transfer-typed transactions are not checked for refunds`() = runBlocking<Unit> {
        val t = txn(user, TransactionType.TRANSFER)
        val r = refundStep.apply(PipelineContext(event(), transaction = t))
        assertEquals(t, (r as StepResult.Continue).context.transaction)
    }

    @Test
    fun `an unrelated credit nearby is not a refund`() = runBlocking<Unit> {
        txns.save(txn(user, TransactionType.CREDIT, amount = "100.00", at = ist(1, 12), merchant = "Acme Payroll"))
        val debit = txn(user, TransactionType.DEBIT, amount = "999.00", at = ist(1, 9), merchant = "Amazon")
        val r = refundStep.apply(PipelineContext(event(), transaction = debit)) as StepResult.Continue
        assertTrue(r.context.relations.isEmpty())
    }

    @Test
    fun `earlier refunds count against the original amount, but rejected and other links do not`() = runBlocking<Unit> {
        val original = txns.save(txn(user, TransactionType.DEBIT, amount = "1000.00", at = ist(1, 9), merchant = "Amazon"))
        val firstRefund = txns.save(txn(user, TransactionType.CREDIT, amount = "600.00", at = ist(3, 9), merchant = "Amazon"))
        val rejected = txns.save(txn(user, TransactionType.CREDIT, amount = "900.00", at = ist(3, 10), merchant = "Amazon Pay"))
        fun link(related: UUID?, relation: LinkRelation, kind: LinkKind) = TransactionLink(userId = user, transactionId = original.id,
            relatedTransactionId = related, source = "sms", kind = kind, relation = relation, score = 8, reason = "t", rulesVersion = "v")
        links.save(link(firstRefund.id, LinkRelation.REFUND, LinkKind.MERGED))
        links.save(link(rejected.id, LinkRelation.REFUND, LinkKind.REJECTED))
        links.save(link(rejected.id, LinkRelation.TRANSFER, LinkKind.SUGGESTED))
        links.save(link(null, LinkRelation.REFUND, LinkKind.MERGED))
        links.save(link(UUID.randomUUID(), LinkRelation.REFUND, LinkKind.MERGED)) // points at a deleted transaction

        val incoming = txn(user, TransactionType.CREDIT, amount = "500.00", at = ist(5, 9), merchant = "Amazon")
        val r = refundStep.apply(PipelineContext(event(direction = IngestDirection.CREDIT, amount = "500.00"), transaction = incoming)) as StepResult.Continue
        assertTrue(r.context.relations.none { it.existing.id == original.id }, "600 + 500 would exceed the 1000 paid")
    }

    @Test
    fun `match candidates carry the sources already merged into them`() = runBlocking<Unit> {
        val existing = txns.save(txn(user, TransactionType.DEBIT, source = "statement"))
        val other = txns.save(txn(user, TransactionType.DEBIT, source = "gmail"))
        links.save(TransactionLink(userId = user, transactionId = existing.id, source = "sms", kind = LinkKind.MERGED, score = 100, reason = "ref", rulesVersion = "v"))
        val candidates = RepositoryMatchCandidates(txns, links).candidatesFor(txn(user, TransactionType.DEBIT, source = "whatsapp"))
        assertEquals(setOf("sms"), candidates.single { it.transaction.id == existing.id }.mergedSources)
        assertEquals(emptySet(), candidates.single { it.transaction.id == other.id }.mergedSources)
    }

    // ── Processor ──

    private val events = InMemoryIngestEventRepository()
    private val writer = RepositoryOutcomeWriter(txns, events, links)

    @Test
    fun `a step failing without a message is recorded as failed with the exception type`() = runBlocking<Unit> {
        val pipeline = IngestPipeline(listOf(ToTransactionStep(), IngestStep { throw IllegalStateException() }))
        events.saveNew(listOf(event()))
        IngestProcessor(events, pipeline, writer).processBatch()
        val e = events.findByUser(user).single()
        assertEquals(IngestEventStatus.FAILED, e.status)
        assertEquals("IllegalStateException: null", e.error)
    }

    /** Wraps the outbox: the first claim fails (database down), later claims work and are signalled. */
    private class FlakyOutbox(private val inner: IngestEventRepository) : IngestEventRepository by inner {
        var claims = 0
        val recovered = CompletableDeferred<Unit>()
        override suspend fun claim(limit: Int, staleBefore: Instant): List<IngestEvent> {
            claims++
            if (claims == 1) throw IllegalStateException("connection refused")
            return inner.claim(limit, staleBefore).also { if (it.isEmpty()) recovered.complete(Unit) }
        }
    }

    @Test
    fun `the background processor drains the backlog in batches and survives a failed batch`() = runBlocking<Unit> {
        repeat(3) { events.saveNew(listOf(event())) }
        val outbox = FlakyOutbox(events)
        val pipeline = IngestPipeline(listOf(ToTransactionStep()))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            IngestProcessor(outbox, pipeline, writer, batchSize = 1).start(scope, sweepEvery = 10.milliseconds)
            withTimeout(5_000) { outbox.recovered.await() }
        } finally {
            scope.cancel()
        }
        assertTrue(events.findByUser(user).all { it.status == IngestEventStatus.PROCESSED })
        assertTrue(outbox.claims >= 5, "1 failed + 3 single-event batches + 1 empty, got ${outbox.claims}")
    }

    @Test
    fun `wake asks for a prompt run`() = runBlocking<Unit> {
        val outbox = FlakyOutbox(events)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val processor = IngestProcessor(outbox, IngestPipeline(listOf(ToTransactionStep())), writer)
            processor.start(scope, sweepEvery = kotlin.time.Duration.INFINITE)
            processor.wake()
            withTimeout(5_000) { outbox.recovered.await() }
        } finally {
            scope.cancel()
        }
        assertEquals(2, outbox.claims, "the failed claim, then the one the wake-up triggered")
    }
}
