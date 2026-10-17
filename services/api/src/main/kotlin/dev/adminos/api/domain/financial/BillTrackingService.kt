package dev.adminos.api.domain.financial

import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.math.abs

/**
 * Bill tracking and payment matching service.
 * Fuzzy-matches transactions to pending bills and detects overdue bills.
 */
class BillTrackingService(
    private val billRepository: BillRepository,
    private val transactionRepository: TransactionRepository
) {
    private val logger = LoggerFactory.getLogger(BillTrackingService::class.java)

    /**
     * Marks the bill [transaction] pays as paid (AOS-508; rules in [BillPaymentMatcher]).
     * Idempotent: a paid bill, or one already linked to a payment, is never matched again.
     * @return the updated bill, or null if the payment pays no bill (or it's ambiguous)
     */
    suspend fun matchPayment(transaction: Transaction): Bill? {
        if (!BillPaymentMatcher.canPayBills(transaction)) return null
        val match = BillPaymentMatcher.billFor(transaction, billRepository.findPendingBills(transaction.userId)) ?: return null
        return markPaid(match.bill, transaction, match.coverage, match.evidence)
    }

    /**
     * Daily sweep (AOS-508): bills detected after their payment was already recorded (an email that
     * arrives late) are matched against the payments in their window, so no reminder goes out for a
     * bill that's paid. @return how many bills were marked paid
     */
    suspend fun matchEarlierPayments(): Int =
        matchEarlierPayments((billRepository.findUpcoming(dev.adminos.api.domain.notifications.ReminderEngine.MAX_REMINDER_DAYS + 1) + billRepository.findOverdue()).distinctBy { it.id })

    suspend fun matchEarlierPayments(bills: List<Bill>): Int {
        var paid = 0
        for (bill in bills) {
            val window = BillPaymentMatcher.window(bill)
            val payments = transactionRepository.findByUserId(
                userId = bill.userId,
                from = window.start.atStartOfDay(BillPaymentMatcher.ZONE).toInstant(),
                to = window.endInclusive.plusDays(1).atStartOfDay(BillPaymentMatcher.ZONE).toInstant(),
                type = TransactionType.DEBIT,
                limit = 1000
            )
            val payment = BillPaymentMatcher.paymentFor(bill, payments) ?: continue
            val coverage = BillPaymentMatcher.coverage(bill, payment.amount) ?: continue
            markPaid(bill, payment, coverage, evidence = null)
            paid++
        }
        return paid
    }

    private suspend fun markPaid(bill: Bill, payment: Transaction, coverage: PaymentCoverage, evidence: BillEvidence?): Bill {
        val updated = billRepository.update(bill.copy(
            status = BillStatus.PAID,
            paidAt = payment.transactedAt,
            paidAmount = payment.amount,
            paymentTxnId = payment.id,
            metadata = (bill.metadata ?: emptyMap()) + mapOf("paidCoverage" to coverage.name.lowercase()) +
                (evidence?.let { mapOf("matchedBy" to it.name.lowercase()) } ?: mapOf("matchedBy" to "sweep"))
        ))
        logger.info("Bill {} marked paid by transaction {} ({})", bill.id, payment.id, coverage)
        return updated
    }

    /**
     * Mark overdue bills — bills whose due_date has passed without payment.
     */
    suspend fun markOverdueBills(): List<Bill> {
        val overdue = billRepository.findOverdue()
        return overdue.map { bill ->
            val updated = billRepository.update(bill.copy(status = BillStatus.OVERDUE))
            logger.info("Bill {} marked overdue", bill.id)
            updated
        }
    }

    suspend fun getUserBills(
        userId: UUID,
        status: BillStatus? = null,
        from: LocalDate? = null,
        to: LocalDate? = null,
        cursor: String? = null,
        limit: Int = 20
    ): List<Bill> = billRepository.findByUserId(userId, status, from, to, cursor, limit)

    suspend fun countUserBills(userId: UUID, status: BillStatus? = null): Long =
        billRepository.countByUserId(userId, status)

    suspend fun getUpcomingBills(userId: UUID, withinDays: Int = 30): List<Bill> =
        billRepository.findUpcoming(userId, withinDays)

    suspend fun getBill(id: UUID): Bill? = billRepository.findById(id)

    companion object {
        /**
         * Contains-based fuzzy matching.
         * Returns a similarity score between 0.0 and 1.0.
         * Uses a combination of contains-check and character overlap.
         */
        fun fuzzyMatch(a: String, b: String): Double {
            val la = a.lowercase().trim()
            val lb = b.lowercase().trim()

            if (la == lb) return 1.0
            if (la.contains(lb) || lb.contains(la)) return 0.9

            // Jaro-Winkler-like similarity
            return jaroWinklerSimilarity(la, lb)
        }

        fun jaroWinklerSimilarity(s1: String, s2: String): Double {
            if (s1 == s2) return 1.0
            if (s1.isEmpty() || s2.isEmpty()) return 0.0

            val matchDistance = (maxOf(s1.length, s2.length) / 2) - 1
            val s1Matches = BooleanArray(s1.length)
            val s2Matches = BooleanArray(s2.length)

            var matches = 0
            var transpositions = 0

            for (i in s1.indices) {
                val start = maxOf(0, i - matchDistance)
                val end = minOf(i + matchDistance + 1, s2.length)
                for (j in start until end) {
                    if (s2Matches[j] || s1[i] != s2[j]) continue
                    s1Matches[i] = true
                    s2Matches[j] = true
                    matches++
                    break
                }
            }

            if (matches == 0) return 0.0

            var k = 0
            for (i in s1.indices) {
                if (!s1Matches[i]) continue
                while (!s2Matches[k]) k++
                if (s1[i] != s2[k]) transpositions++
                k++
            }

            val jaro = (matches.toDouble() / s1.length +
                matches.toDouble() / s2.length +
                (matches - transpositions / 2.0) / matches) / 3.0

            // Winkler bonus for common prefix (up to 4 chars)
            var prefix = 0
            for (i in 0 until minOf(4, minOf(s1.length, s2.length))) {
                if (s1[i] == s2[i]) prefix++ else break
            }

            return jaro + prefix * 0.1 * (1 - jaro)
        }

        /** |expected − actual| ≤ tolerance × |expected|, exactly (AOS-612: ₹0.00 used to divide by zero). */
        fun isAmountWithinTolerance(expected: BigDecimal, actual: BigDecimal, tolerance: Double): Boolean {
            if (expected.signum() == 0) return actual.signum() == 0
            return expected.subtract(actual).abs() <= expected.abs().multiply(BigDecimal.valueOf(tolerance))
        }
    }
}
