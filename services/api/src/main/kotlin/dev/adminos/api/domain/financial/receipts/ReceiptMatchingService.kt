package dev.adminos.api.domain.financial.receipts

import dev.adminos.api.domain.common.TimeRange
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionRepository
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.financial.matching.PaymentClaim
import dev.adminos.api.domain.financial.matching.PaymentMatch
import dev.adminos.api.domain.financial.matching.PaymentMatcher
import dev.adminos.api.domain.financial.matching.PaymentWindow
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** Why a user's match decision was refused. Routes map these to HTTP (ENGINEERING §2). */
sealed class ReceiptMatchError(val reason: String) : RuntimeException(reason) {
    class ReceiptNotFound : ReceiptMatchError("Receipt not found")
    class PaymentNotFound : ReceiptMatchError("Payment not found")
    class NotAPayment : ReceiptMatchError("Only a spend can pay an order")
    class PaymentTaken : ReceiptMatchError("That payment is already another order's")
    class NothingToReject : ReceiptMatchError("The receipt has no payment to reject")
}

/** What a sweep did, as counts only. */
data class ReceiptMatchCounts(val checked: Int, val matched: Int, val suggested: Int, val noPayment: Int)

/**
 * Ties each order receipt to the payment that paid it (AOS-903, D-88), in both directions like bill
 * payments (D-54): when a payment is stored ([onPayment], from a listener) and on a sweep for receipts
 * that came after their payment ([matchPending]). Rules are in [PaymentMatcher]; this service finds
 * the candidates, keeps payments 1:1 and remembers what the user rejected.
 */
class ReceiptMatchingService(
    private val receipts: OrderReceiptRepository,
    private val transactions: TransactionRepository,
    private val matcher: PaymentMatcher = PaymentMatcher(),
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = LoggerFactory.getLogger(ReceiptMatchingService::class.java)

    companion object {
        /** Payment from 15 minutes before the order to 3 hours after it; date-only rows the same or next day, cards −1…+3. */
        val WINDOW = PaymentWindow(before = Duration.ofMinutes(15), after = Duration.ofHours(3), days = 0..1, cardDays = -1..3)
        /** Card statements post days later: the widest span to look in. */
        private val LOOK_AROUND: Duration = Duration.ofDays(4)
        val SWEEP_LOOKBACK: Duration = Duration.ofDays(30)
        const val SWEEP_BATCH = 500
    }

    /** A new payment: does it pay an unmatched order nearby? @return the receipt it now pays or is suggested for */
    suspend fun onPayment(payment: Transaction): OrderReceipt? {
        if (payment.type != TransactionType.DEBIT || payment.nature != TransactionNature.SPEND) return null
        val nearby = receipts.findUnmatchedForUser(payment.userId,
            TimeRange(payment.transactedAt.minus(LOOK_AROUND), payment.transactedAt.plus(LOOK_AROUND)))
            .filter { r -> r.expectedPaymentAmounts().any { it.compareTo(payment.amount) == 0 } }
            .sortedBy { Duration.between(it.orderedAt, payment.transactedAt).abs() }
        for (receipt in nearby) {
            val result = match(receipt)
            if (result.match.transactionId == payment.id) return result
        }
        return null
    }

    /** The sweep: every unmatched receipt of the last 30 days, oldest first. */
    suspend fun matchPending(): ReceiptMatchCounts {
        val pending = receipts.findUnmatched(clock.instant().minus(SWEEP_LOOKBACK), SWEEP_BATCH)
        val after = pending.map { match(it).match.state }
        return ReceiptMatchCounts(
            checked = pending.size,
            matched = after.count { it == ReceiptMatchState.MATCHED },
            suggested = after.count { it == ReceiptMatchState.SUGGESTED },
            noPayment = after.count { it == ReceiptMatchState.NO_PAYMENT }
        )
    }

    /** Matches one unmatched receipt. Idempotent: a receipt already decided is returned unchanged. */
    suspend fun match(receipt: OrderReceipt): OrderReceipt {
        if (receipt.match.state != ReceiptMatchState.UNMATCHED) return receipt
        if (!receipt.paymentMode.expectsBankPayment) return receipts.updateMatch(receipt.id, ReceiptMatch.NO_PAYMENT, receipt.rejectedTransactionIds)
        val amounts = receipt.expectedPaymentAmounts().takeIf { it.isNotEmpty() } ?: return receipt
        val claim = PaymentClaim(receipt.userId, amounts, receipt.orderedAt, WINDOW, receipt.app, receipt.paymentMode.paymentMethod)
        val pool = amounts.flatMap { amount ->
            transactions.findMatchCandidates(receipt.userId, TransactionType.DEBIT, amount,
                receipt.orderedAt.minus(LOOK_AROUND), receipt.orderedAt.plus(LOOK_AROUND))
        }.distinctBy { it.id }
        if (pool.isEmpty()) return receipt
        val taken = receipts.matchedTransactionIds(pool.map { it.id })
        val decided = when (val d = matcher.decide(claim, pool, excluded = taken + receipt.rejectedTransactionIds)) {
            PaymentMatch.None -> return receipt
            is PaymentMatch.Auto -> ReceiptMatch(ReceiptMatchState.MATCHED, d.payment.id, d.score, d.reason.take(200), MatchedBy.RULES)
            is PaymentMatch.Suggest -> ReceiptMatch(ReceiptMatchState.SUGGESTED, d.payment.id, d.score, d.reason.take(200), MatchedBy.RULES)
        }
        logger.info("Receipt {} {} to transaction {} (score {})", receipt.id, decided.state.name.lowercase(), decided.transactionId, decided.score)
        return receipts.updateMatch(receipt.id, decided, receipt.rejectedTransactionIds)
    }

    /** The user says [transactionId] paid the receipt (confirming a suggestion, or choosing one). */
    suspend fun confirm(userId: UUID, receiptId: UUID, transactionId: UUID): OrderReceipt {
        val receipt = owned(userId, receiptId)
        val payment = transactions.findById(transactionId)?.takeIf { it.userId == userId } ?: throw ReceiptMatchError.PaymentNotFound()
        if (payment.type != TransactionType.DEBIT || payment.nature != TransactionNature.SPEND) throw ReceiptMatchError.NotAPayment()
        val takenByOther = receipts.matchedTransactionIds(listOf(payment.id)).isNotEmpty() &&
            !(receipt.match.state == ReceiptMatchState.MATCHED && receipt.match.transactionId == payment.id)
        if (takenByOther) throw ReceiptMatchError.PaymentTaken()
        val match = ReceiptMatch(ReceiptMatchState.MATCHED, payment.id, receipt.match.score, "confirmed by you", MatchedBy.USER)
        return receipts.updateMatch(receipt.id, match, receipt.rejectedTransactionIds - payment.id)
    }

    /**
     * The user says the linked or suggested payment is not this order's: it is remembered and never
     * proposed again, and the receipt is matched afresh without it.
     */
    suspend fun reject(userId: UUID, receiptId: UUID): OrderReceipt {
        val receipt = owned(userId, receiptId)
        val rejected = receipt.match.transactionId ?: throw ReceiptMatchError.NothingToReject()
        val reset = receipts.updateMatch(receipt.id, ReceiptMatch.UNMATCHED, receipt.rejectedTransactionIds + rejected)
        return match(reset)
    }

    /** The user's own receipt; another user's is "not found" (never "forbidden", which would confirm it exists). */
    private suspend fun owned(userId: UUID, receiptId: UUID): OrderReceipt =
        receipts.findById(receiptId)?.takeIf { it.userId == userId } ?: throw ReceiptMatchError.ReceiptNotFound()
}
