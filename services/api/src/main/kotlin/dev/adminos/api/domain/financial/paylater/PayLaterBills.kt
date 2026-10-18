package dev.adminos.api.domain.financial.paylater

import dev.adminos.api.domain.financial.Bill
import dev.adminos.api.domain.financial.BillRepository
import dev.adminos.api.domain.financial.BillStatus
import dev.adminos.api.domain.financial.BillTrackingService
import dev.adminos.api.domain.financial.BillType
import dev.adminos.api.domain.ingestion.sms.PayLaterBillNotice
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

sealed interface PayLaterBillOutcome {
    val bill: Bill
    data class Created(override val bill: Bill) : PayLaterBillOutcome
    /** The same bill was already known (a reminder, or the same bill by SMS and email). */
    data class Known(override val bill: Bill) : PayLaterBillOutcome
}

/**
 * Turns a provider's bill notice into a bill in the dues calendar (AOS-909, D-89), so the bill
 * reminders (AOS-503) apply. Providers send reminders for the same bill, and the same bill may come
 * by SMS and email: same provider and due date (or within 2 days with the same amount) is one bill.
 */
class PayLaterBills(private val bills: BillRepository, private val billing: BillTrackingService) {
    private val logger = LoggerFactory.getLogger(PayLaterBills::class.java)

    suspend fun record(userId: UUID, notice: PayLaterBillNotice, receivedAt: Instant, sourceRef: String, source: String): PayLaterBillOutcome {
        val nearby = bills.findByUserId(userId, from = notice.dueDate.minusDays(SAME_BILL_DAYS), to = notice.dueDate.plusDays(SAME_BILL_DAYS), limit = 100)
            .filter { it.billType == BillType.PAY_LATER && it.status != BillStatus.CANCELLED && it.billerName.equals(notice.provider, ignoreCase = true) }
        val known = nearby.firstOrNull { it.dueDate == notice.dueDate } ?: nearby.firstOrNull { it.amount.compareTo(notice.amount) == 0 }
        if (known != null) {
            val filled = if (known.minimumDue == null && notice.minimumDue != null) bills.update(known.copy(minimumDue = notice.minimumDue)) else known
            return PayLaterBillOutcome.Known(filled)
        }
        val saved = bills.save(Bill(
            userId = userId, billType = BillType.PAY_LATER, billerName = notice.provider, amount = notice.amount,
            minimumDue = notice.minimumDue, dueDate = notice.dueDate, detectionSource = source, sourceRef = sourceRef,
            createdAt = receivedAt, updatedAt = receivedAt
        ))
        logger.info("Pay-later bill {} recorded for user {} (due {})", saved.id, userId, saved.dueDate)
        // A repayment made before the bill was seen pays it now, so no reminder goes out for it (D-54).
        billing.matchEarlierPayments(listOf(saved))
        return PayLaterBillOutcome.Created(bills.findById(saved.id) ?: saved)
    }

    private companion object {
        const val SAME_BILL_DAYS = 2L
    }
}
