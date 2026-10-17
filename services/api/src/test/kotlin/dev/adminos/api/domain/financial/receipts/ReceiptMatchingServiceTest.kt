package dev.adminos.api.domain.financial.receipts

import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.receipts.ReceiptFixtures.ORDER_AT
import dev.adminos.api.domain.financial.receipts.ReceiptFixtures.payment
import dev.adminos.api.domain.financial.receipts.ReceiptFixtures.receipt
import dev.adminos.api.domain.scheduling.JobName
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-903: each order receipt is tied to the payment that paid it, never counted as a second spend. */
class ReceiptMatchingServiceTest {
    private val user = UUID.randomUUID()
    private val receipts = InMemoryOrderReceiptRepository()
    private val txns = InMemoryTransactionRepository()
    private val clock = Clock.fixed(ORDER_AT.plus(Duration.ofHours(1)), ZoneOffset.UTC)
    private val service = ReceiptMatchingService(receipts, txns, clock = clock)

    @Test
    fun `a receipt that arrives after its payment is matched by the sweep`() = runBlocking<Unit> {
        val paid = txns.save(payment(user))
        val r = receipts.save(receipt(user))
        val counts = service.matchPending()
        assertEquals(ReceiptMatchCounts(checked = 1, matched = 1, suggested = 0, noPayment = 0), counts)
        val m = receipts.findById(r.id)?.match
        assertEquals(ReceiptMatchState.MATCHED, m?.state)
        assertEquals(paid.id, m?.transactionId)
        assertEquals(MatchedBy.RULES, m?.matchedBy)
        assertEquals(ReceiptMatchCounts(0, 0, 0, 0), service.matchPending(), "a decided receipt is not looked at again")
    }

    @Test
    fun `a payment that arrives after its receipt is matched by the listener`() = runBlocking<Unit> {
        val r = receipts.save(receipt(user))
        val paid = txns.save(payment(user))
        ReceiptPaymentListener(service).onCreated(paid)
        assertEquals(paid.id, receipts.findById(r.id)?.match?.transactionId)
    }

    @Test
    fun `the listener ignores money that can't pay an order and payments for no order`() = runBlocking<Unit> {
        receipts.save(receipt(user))
        assertNull(service.onPayment(payment(user).copy(nature = TransactionNature.TRANSFER)))
        assertNull(service.onPayment(payment(user, amount = "100.00")))
        val credit = payment(user).copy(type = dev.adminos.api.domain.financial.TransactionType.CREDIT, nature = TransactionNature.INCOME)
        assertNull(service.onPayment(credit))
    }

    @Test
    fun `when the nearest order is paid by another payment, the listener keeps looking`() = runBlocking<Unit> {
        val near = receipts.save(receipt(user, at = ORDER_AT))
        val later = receipts.save(receipt(user, at = ORDER_AT.plus(Duration.ofMinutes(24))))
        val first = txns.save(payment(user, at = ORDER_AT.plusSeconds(60)))
        val second = txns.save(payment(user, at = ORDER_AT.plus(Duration.ofMinutes(10)), merchant = "PayU Payments"))
        val got = service.onPayment(second)
        assertEquals(later.id, got?.id, "the second payment is proposed for the later order")
        assertEquals(ReceiptMatchState.SUGGESTED, got?.match?.state)
        assertEquals(first.id, receipts.findById(near.id)?.match?.transactionId, "the nearer order is paid by the first payment")
    }

    @Test
    fun `wallet, pay-later and cash on delivery orders expect no bank payment`() = runBlocking<Unit> {
        listOf(ReceiptPaymentMode.WALLET, ReceiptPaymentMode.PAY_LATER, ReceiptPaymentMode.COD).forEach { mode ->
            txns.save(payment(user))
            val r = receipts.save(receipt(user, mode = mode))
            assertEquals(ReceiptMatchState.NO_PAYMENT, service.match(r).match.state, "$mode")
        }
    }

    @Test
    fun `a split payment matches only on the part charged to the bank`() = runBlocking<Unit> {
        val upi = txns.save(payment(user, amount = "350.00"))
        txns.save(payment(user, amount = "450.00", at = ORDER_AT.plusSeconds(10)))
        val r = receipts.save(receipt(user, total = "450.00", mode = ReceiptPaymentMode.SPLIT, paid = "350.00"))
        assertEquals(upi.id, service.match(r).match.transactionId)
        val unknownPart = receipts.save(receipt(user, total = "450.00", mode = ReceiptPaymentMode.SPLIT))
        assertEquals(ReceiptMatchState.UNMATCHED, service.match(unknownPart).match.state, "the bank part is unknown: nothing to match on")
    }

    @Test
    fun `a payment pays one order only`() = runBlocking<Unit> {
        val paid = txns.save(payment(user))
        val first = service.match(receipts.save(receipt(user)))
        val second = service.match(receipts.save(receipt(user, at = ORDER_AT.plusSeconds(120))))
        assertEquals(paid.id, first.match.transactionId)
        assertEquals(ReceiptMatchState.UNMATCHED, second.match.state, "the same ₹487 can't pay two orders")
    }

    @Test
    fun `a missing payment leaves the receipt unmatched, still counted for fees`() = runBlocking<Unit> {
        val r = receipts.save(receipt(user))
        assertEquals(ReceiptMatchState.UNMATCHED, service.match(r).match.state)
        assertTrue(r.counts)
        val noTotal = receipts.save(receipt(user, total = null, status = ReceiptStatus.CANCELLED))
        assertEquals(ReceiptMatchState.UNMATCHED, service.match(noTotal).match.state, "a cancellation-only row has no amount")
    }

    @Test
    fun `a payment that doesn't name the app is suggested, then confirmed by the user`() = runBlocking<Unit> {
        val p = txns.save(payment(user, merchant = "PayU Payments"))
        val r = service.match(receipts.save(receipt(user)))
        assertEquals(ReceiptMatchState.SUGGESTED, r.match.state)
        val confirmed = service.confirm(user, r.id, p.id)
        assertEquals(ReceiptMatchState.MATCHED, confirmed.match.state)
        assertEquals(MatchedBy.USER, confirmed.match.matchedBy)
        assertEquals(confirmed, service.confirm(user, r.id, p.id).copy(updatedAt = confirmed.updatedAt), "confirming again is harmless")
    }

    @Test
    fun `a rejected payment is remembered and never proposed again`() = runBlocking<Unit> {
        val wrong = txns.save(payment(user))
        val r = service.match(receipts.save(receipt(user)))
        assertEquals(wrong.id, r.match.transactionId)
        val rejected = service.reject(user, r.id)
        assertEquals(ReceiptMatchState.UNMATCHED, rejected.match.state)
        assertEquals(setOf(wrong.id), rejected.rejectedTransactionIds)
        assertEquals(ReceiptMatchState.UNMATCHED, service.match(rejected).match.state)
        val right = txns.save(payment(user, at = ORDER_AT.plusSeconds(90)))
        assertEquals(right.id, service.match(receipts.findById(r.id) ?: error("gone")).match.transactionId)
        assertEquals(setOf(wrong.id), service.confirm(user, r.id, right.id).rejectedTransactionIds)
        assertEquals(emptySet(), service.confirm(user, r.id, wrong.id).rejectedTransactionIds, "choosing it yourself lifts the rejection")
    }

    @Test
    fun `user decisions are refused for someone else's receipt or payment, and for non-payments`() = runBlocking<Unit> {
        val r = receipts.save(receipt(user))
        val mine = txns.save(payment(user))
        val theirs = txns.save(payment(UUID.randomUUID()))
        assertFailsWith<ReceiptMatchError.ReceiptNotFound> { service.confirm(UUID.randomUUID(), r.id, mine.id) }
        assertFailsWith<ReceiptMatchError.ReceiptNotFound> { service.reject(user, UUID.randomUUID()) }
        assertFailsWith<ReceiptMatchError.PaymentNotFound> { service.confirm(user, r.id, theirs.id) }
        assertFailsWith<ReceiptMatchError.PaymentNotFound> { service.confirm(user, r.id, UUID.randomUUID()) }
        val refund = txns.save(payment(user).copy(nature = TransactionNature.REFUND))
        assertFailsWith<ReceiptMatchError.NotAPayment> { service.confirm(user, r.id, refund.id) }
        val credit = txns.save(payment(user).copy(type = dev.adminos.api.domain.financial.TransactionType.CREDIT))
        assertFailsWith<ReceiptMatchError.NotAPayment> { service.confirm(user, r.id, credit.id) }
        assertFailsWith<ReceiptMatchError.NothingToReject> { service.reject(user, r.id) }
        val other = receipts.save(receipt(user))
        service.confirm(user, other.id, mine.id)
        assertFailsWith<ReceiptMatchError.PaymentTaken> { service.confirm(user, r.id, mine.id) }
    }

    @Test
    fun `the sweep only looks back 30 days`() = runBlocking<Unit> {
        receipts.save(receipt(user, at = clock.instant().minus(Duration.ofDays(31))))
        assertEquals(0, service.matchPending().checked)
    }

    @Test
    fun `the job runs the sweep every 10 minutes and reports counts only`() = runBlocking<Unit> {
        txns.save(payment(user))
        receipts.save(receipt(user))
        receipts.save(receipt(user, mode = ReceiptPaymentMode.WALLET))
        txns.save(payment(user, amount = "199.00", merchant = "Someone"))
        receipts.save(receipt(user, total = "199.00"))
        val job = ReceiptMatchingJob(service)
        assertEquals(JobName("receipt-matching"), job.name)
        assertEquals(dev.adminos.api.domain.scheduling.Schedule.Every(Duration.ofMinutes(10)), job.schedule)
        assertEquals(mapOf("checked" to 3, "matched" to 1, "suggested" to 1, "noPayment" to 1), job.run().counts)
    }

    @Test
    fun `the in-memory store keeps a payment to one order, like the database`() = runBlocking<Unit> {
        val p = txns.save(payment(user))
        val a = receipts.save(receipt(user))
        val b = receipts.save(receipt(user))
        receipts.updateMatch(a.id, ReceiptMatch(ReceiptMatchState.MATCHED, p.id, 12, "x", MatchedBy.RULES), emptySet())
        assertFailsWith<IllegalStateException> { receipts.updateMatch(b.id, ReceiptMatch(ReceiptMatchState.MATCHED, p.id, 12, "x", MatchedBy.RULES), emptySet()) }
        assertFailsWith<NoSuchElementException> { receipts.updateMatch(UUID.randomUUID(), ReceiptMatch.UNMATCHED, emptySet()) }
    }
}
