package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.common.TimeRange
import dev.adminos.api.domain.financial.*
import dev.adminos.api.domain.financial.receipts.*
import dev.adminos.api.domain.financial.receipts.ReceiptFixtures.receipt
import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.events.IngestEvent
import dev.adminos.api.domain.ingestion.events.IngestSource
import dev.adminos.api.domain.ingestion.pipeline.*
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import dev.adminos.api.infrastructure.database.dbQuery
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-903 on Postgres (V50): receipts round-trip, are matched to the SMS payment, and stay 1:1. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderReceiptsIntegrationTest {
    private val receipts = ExposedOrderReceiptRepository()
    private val txns = ExposedTransactionRepository()
    private val events = ExposedIngestEventRepository()
    private val links = ExposedTransactionLinkRepository()
    private val matching = ReceiptMatchingService(receipts, txns)

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private val at = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)

    @Test
    fun `a receipt round-trips with its fees, refunds and match`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val saved = receipts.save(receipt(user, at = at, refunds = listOf("120.00", "120.0"), paid = "487.00",
            fees = mapOf(FeeKind.DELIVERY to "35.00", FeeKind.SMALL_CART to "25.00", FeeKind.TIP to "20.00")))
        val read = receipts.findById(saved.id) ?: error("not stored")
        assertEquals(saved.copy(createdAt = read.createdAt, updatedAt = read.updatedAt, refunds = read.refunds), read)
        assertEquals(listOf(BigDecimal("120.00")), read.refunds, "the same refund twice is one refund")
        assertEquals(BigDecimal("60.00"), read.hiddenFees)
        assertEquals(listOf(saved.id), receipts.findByUser(user, TimeRange(at.minusSeconds(1), at.plusSeconds(1))).map { it.id })
        assertTrue(receipts.findByUser(user, TimeRange(at.plusSeconds(1), at.plusSeconds(2))).isEmpty(), "the range is half-open")
        assertNull(receipts.findById(UUID.randomUUID()))
        assertTrue(receipts.matchedTransactionIds(emptyList()).isEmpty())
        assertFailsWith<NoSuchElementException> { receipts.updateMatch(UUID.randomUUID(), ReceiptMatch.UNMATCHED, emptySet()) }
    }

    @Test
    fun `an SMS payment stored by the pipeline is matched to the waiting receipt, once`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val waiting = receipts.save(receipt(user, total = "612.00", at = at))
        val processor = IngestProcessor(
            events,
            IngestPipeline(listOf(ToTransactionStep(), NormalizeMerchantStep(MerchantNormalizer()), CategorizeStep(CategorizationService(ExposedCategoryRuleRepository())))),
            RepositoryOutcomeWriter(txns, events, links) { w -> dbQuery { w() } },
            listeners = listOf(TransactionCreatedListener { if (it.userId == user) ReceiptPaymentListener(matching).onCreated(it) })
        )
        val sms = IngestEvent(userId = user, source = IngestSource.SMS, sourceRef = UUID.randomUUID().toString(), occurredAt = at.plusSeconds(40),
            direction = IngestDirection.DEBIT, amount = BigDecimal("612.00"), merchantRaw = "UPI-BUNDL TECHNOLOGIES-SWIGGY@ICICI", accountHint = "1234",
            paymentMethod = PaymentMethod.UPI)
        events.saveNew(listOf(sms))
        while (processor.processBatch() > 0) {}

        val payment = txns.findByIngestEventId(sms.id) ?: error("no transaction")
        val matched = receipts.findById(waiting.id) ?: error("gone")
        assertEquals(ReceiptMatchState.MATCHED, matched.match.state)
        assertEquals(payment.id, matched.match.transactionId)
        assertEquals(1, txns.findByUserId(user, limit = 10).size, "the receipt is not a second spend")
        assertEquals(setOf(payment.id), receipts.matchedTransactionIds(listOf(payment.id, UUID.randomUUID())))

        val second = receipts.save(receipt(user, total = "612.00", at = at.plusSeconds(60)))
        assertEquals(ReceiptMatchState.UNMATCHED, matching.match(second).match.state, "one payment, one order")
        assertFailsWith<Exception>("the database keeps payments 1:1 too") {
            receipts.updateMatch(second.id, ReceiptMatch(ReceiptMatchState.MATCHED, payment.id, 12, "forced", MatchedBy.RULES), emptySet())
        }
    }

    @Test
    fun `the sweep reads unmatched receipts only, and rejections are remembered`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val wrong = txns.save(Transaction(userId = user, type = TransactionType.DEBIT, amount = BigDecimal("213.00"), merchantName = "Zepto",
            paymentMethod = PaymentMethod.UPI, transactedAt = at.plusSeconds(30)))
        val r = receipts.save(receipt(user, app = "Zepto", total = "213.00", at = at))
        val wallet = receipts.save(receipt(user, total = "99.00", at = at, mode = ReceiptPaymentMode.WALLET))
        val pending = receipts.findUnmatched(at.minus(Duration.ofDays(1)), 1000).map { it.id }
        assertTrue(r.id in pending && wallet.id in pending)
        matching.matchPending()
        assertEquals(ReceiptMatchState.NO_PAYMENT, receipts.findById(wallet.id)?.match?.state)
        assertEquals(wrong.id, receipts.findById(r.id)?.match?.transactionId)
        val rejected = matching.reject(user, r.id)
        assertEquals(setOf(wrong.id), rejected.rejectedTransactionIds)
        assertEquals(setOf(wrong.id), receipts.findById(r.id)?.rejectedTransactionIds)
        assertTrue(r.id !in receipts.findUnmatched(at.plus(Duration.ofDays(1)), 1000).map { it.id }, "since is respected")
    }

    @Test
    fun `a receipt whose payment was deleted reads as unmatched again`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val p = txns.save(Transaction(userId = user, type = TransactionType.DEBIT, amount = BigDecimal("300.00"), merchantName = "Swiggy",
            transactedAt = at.plusSeconds(20)))
        val r = matching.match(receipts.save(receipt(user, total = "300.00", at = at)))
        assertEquals(p.id, r.match.transactionId)
        txns.delete(p.id)
        val read = receipts.findById(r.id) ?: error("gone")
        assertEquals(ReceiptMatch.UNMATCHED, read.match)
        assertTrue(read.id in receipts.findUnmatchedForUser(user, TimeRange(at.minusSeconds(60), at.plusSeconds(60))).map { it.id })
        assertTrue(read.id in receipts.findUnmatched(at.minusSeconds(60), 1000).map { it.id }, "the sweep matches it again")
        val again = txns.save(Transaction(userId = user, type = TransactionType.DEBIT, amount = BigDecimal("300.00"), merchantName = "Swiggy",
            transactedAt = at.plusSeconds(25)))
        matching.matchPending()
        assertEquals(again.id, receipts.findById(r.id)?.match?.transactionId)
    }
}
