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
     * Try to match a transaction to a pending bill.
     * Uses fuzzy name matching (>0.8 similarity) and amount within 5% tolerance.
     */
    suspend fun matchPayment(transaction: Transaction): Bill? {
        val merchantName = transaction.merchantName ?: return null
        val pendingBills = billRepository.findPendingBills(transaction.userId)

        for (bill in pendingBills) {
            val nameSimilarity = fuzzyMatch(bill.billerName, merchantName)
            val amountMatch = isAmountWithinTolerance(bill.amount, transaction.amount, 0.05)

            if (nameSimilarity > 0.8 && amountMatch) {
                val updated = billRepository.update(bill.copy(
                    status = BillStatus.PAID,
                    paidAt = transaction.transactedAt,
                    paidAmount = transaction.amount,
                    paymentTxnId = transaction.id
                ))
                logger.info("Matched bill {} to transaction {}", bill.id, transaction.id)
                return updated
            }
        }
        return null
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

        fun isAmountWithinTolerance(expected: BigDecimal, actual: BigDecimal, tolerance: Double): Boolean {
            if (expected == BigDecimal.ZERO) return actual == BigDecimal.ZERO
            val diff = abs(expected.subtract(actual).toDouble())
            return diff / expected.toDouble() <= tolerance
        }
    }
}
