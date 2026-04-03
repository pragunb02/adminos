package dev.adminos.api.domain.financial

import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for BillTrackingService — payment matching and overdue detection.
 *
 * _Requirements: 9.4, 9.5_
 */
class BillTrackingServiceTest {

    private val billRepo = InMemoryBillRepository()
    private val txnRepo = InMemoryTransactionRepository()
    private val service = BillTrackingService(billRepo, txnRepo)

    // ── Fuzzy matching ──

    @Test
    fun `fuzzyMatch returns 1 for identical strings`() {
        assertEquals(1.0, BillTrackingService.fuzzyMatch("Netflix", "Netflix"))
    }

    @Test
    fun `fuzzyMatch returns high score for contains match`() {
        val score = BillTrackingService.fuzzyMatch("Netflix Subscription", "Netflix")
        assert(score >= 0.8) { "Contains match should score >= 0.8, got $score" }
    }

    @Test
    fun `fuzzyMatch returns high score for similar names`() {
        val score = BillTrackingService.fuzzyMatch("HDFC Credit Card", "HDFC Credit Card Bill")
        assert(score >= 0.8) { "Similar names should score >= 0.8, got $score" }
    }

    @Test
    fun `fuzzyMatch returns low score for unrelated names`() {
        val score = BillTrackingService.fuzzyMatch("Netflix", "Electricity Board")
        assert(score < 0.7) { "Unrelated names should score < 0.7, got $score" }
    }

    // ── Amount tolerance ──

    @Test
    fun `amount within 5 percent tolerance matches`() {
        assert(BillTrackingService.isAmountWithinTolerance(
            BigDecimal("1000.00"), BigDecimal("1040.00"), 0.05
        ))
    }

    @Test
    fun `amount outside 5 percent tolerance does not match`() {
        assert(!BillTrackingService.isAmountWithinTolerance(
            BigDecimal("1000.00"), BigDecimal("1060.00"), 0.05
        ))
    }

    @Test
    fun `exact amount matches`() {
        assert(BillTrackingService.isAmountWithinTolerance(
            BigDecimal("500.00"), BigDecimal("500.00"), 0.05
        ))
    }

    // ── Payment matching ──

    @Test
    fun `matchPayment links transaction to matching bill`() = runBlocking {
        val userId = UUID.randomUUID()
        val bill = Bill(
            userId = userId,
            billerName = "Netflix",
            amount = BigDecimal("499.00"),
            dueDate = LocalDate.now().plusDays(3),
            status = BillStatus.UPCOMING,
            detectionSource = "gmail"
        )
        billRepo.save(bill)

        val txn = Transaction(
            userId = userId,
            type = TransactionType.DEBIT,
            amount = BigDecimal("499.00"),
            merchantName = "Netflix Subscription",
            transactedAt = Instant.now()
        )

        val matched = service.matchPayment(txn)

        assertNotNull(matched)
        assertEquals(BillStatus.PAID, matched.status)
        assertEquals(txn.id, matched.paymentTxnId)
        assertNotNull(matched.paidAt)
    }

    @Test
    fun `matchPayment returns null when no bill matches`() = runBlocking {
        val userId = UUID.randomUUID()
        val txn = Transaction(
            userId = userId,
            type = TransactionType.DEBIT,
            amount = BigDecimal("999.00"),
            merchantName = "Random Store",
            transactedAt = Instant.now()
        )

        val matched = service.matchPayment(txn)
        assertNull(matched)
    }

    @Test
    fun `matchPayment does not match when amount differs by more than 5 percent`() = runBlocking {
        val userId = UUID.randomUUID()
        val bill = Bill(
            userId = userId,
            billerName = "HDFC Credit Card",
            amount = BigDecimal("10000.00"),
            dueDate = LocalDate.now().plusDays(3),
            status = BillStatus.DUE,
            detectionSource = "gmail"
        )
        billRepo.save(bill)

        val txn = Transaction(
            userId = userId,
            type = TransactionType.DEBIT,
            amount = BigDecimal("5000.00"),
            merchantName = "HDFC Credit Card Payment",
            transactedAt = Instant.now()
        )

        val matched = service.matchPayment(txn)
        assertNull(matched)
    }

    // ── Overdue detection ──

    @Test
    fun `markOverdueBills transitions past-due bills to overdue`() = runBlocking {
        val userId = UUID.randomUUID()
        val bill = Bill(
            userId = userId,
            billerName = "Electricity Board",
            amount = BigDecimal("2500.00"),
            dueDate = LocalDate.now().minusDays(2),
            status = BillStatus.DUE,
            detectionSource = "gmail"
        )
        billRepo.save(bill)

        val overdue = service.markOverdueBills()

        assertEquals(1, overdue.size)
        assertEquals(BillStatus.OVERDUE, overdue.first().status)
    }

    @Test
    fun `markOverdueBills does not affect already paid bills`() = runBlocking {
        val userId = UUID.randomUUID()
        val bill = Bill(
            userId = userId,
            billerName = "Internet Bill",
            amount = BigDecimal("999.00"),
            dueDate = LocalDate.now().minusDays(1),
            status = BillStatus.PAID,
            detectionSource = "gmail"
        )
        billRepo.save(bill)

        val overdue = service.markOverdueBills()
        assertEquals(0, overdue.size)
    }
}
