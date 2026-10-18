package dev.adminos.api.domain.financial

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/** How a payment covers a bill. */
enum class PaymentCoverage { FULL, MINIMUM_DUE }

/** Why we believe a payment is for a bill, strongest first. */
enum class BillEvidence(val strength: Int) {
    /** The bill's account/card number (last 4) appears in the payment. */
    ACCOUNT_NUMBER(3),
    /** The payee's name matches the biller's. */
    BILLER_NAME(2),
    /** A credit-card bill payment, and this is the user's only card bill that fits. */
    ONLY_CARD_BILL(1)
}

data class BillMatch(val bill: Bill, val coverage: PaymentCoverage, val evidence: BillEvidence)

/**
 * Decides which bill a payment pays (AOS-508). Pure: no I/O, so every rule is tested directly.
 *
 * A payment matches a bill only when all three hold:
 * 1. **Amount:** within 5% of the bill, or equal (±1%) to its minimum due;
 * 2. **Date:** in the bill's payment window — from its billing period's end (or 40 days before the
 *    due date) to 15 days after the due date (late payments count);
 * 3. **Evidence** that it's this biller: the account number, the biller's name, or — for a credit-card
 *    bill paid as a card-bill transfer — it being the only card bill that fits.
 * With several candidates the strongest evidence wins, then the closer amount, then the nearer due
 * date. Two equally good candidates are **ambiguous** and nothing is marked paid.
 */
object BillPaymentMatcher {
    val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    private const val NAME_SIMILARITY = 0.8
    private val FULL_TOLERANCE = 0.05
    private val MINIMUM_TOLERANCE = 0.01
    private const val WINDOW_BEFORE_DUE_DAYS = 40L
    private const val GRACE_AFTER_DUE_DAYS = 15L

    /**
     * Money leaving the account that can pay a bill: a spend, or a card-bill or pay-later repayment transfer (AOS-208, AOS-909).
     * A share owed to a friend on Splitwise (AOS-911) left no account, so it pays nothing.
     */
    fun canPayBills(t: Transaction): Boolean =
        t.type == TransactionType.DEBIT && t.sourceType != dev.adminos.api.domain.financial.splits.SplitMatcher.SPLITWISE_SOURCE &&
            (t.nature == TransactionNature.SPEND || (t.nature == TransactionNature.TRANSFER && t.subcategory in BILL_TRANSFERS))

    /** The bill [payment] pays among [bills] (pending ones of the same user), or null if none or ambiguous. */
    fun billFor(payment: Transaction, bills: List<Bill>): BillMatch? {
        if (!canPayBills(payment)) return null
        val open = bills.filter { it.userId == payment.userId && it.status in PAYABLE && it.paymentTxnId == null }
        val cardBillsThatFit = open.count { it.billType == BillType.CREDIT_CARD && coverage(it, payment.amount) != null && inWindow(it, payment) }
        val matches = open.mapNotNull { bill -> match(bill, payment, onlyCardBill = cardBillsThatFit == 1) }
        return pickUnambiguous(matches, payment.amount)
    }

    /** The payment among [payments] that pays [bill] (for the daily sweep), or null if none or ambiguous. */
    fun paymentFor(bill: Bill, payments: List<Transaction>): Transaction? {
        if (bill.status !in PAYABLE || bill.paymentTxnId != null) return null
        val candidates = payments.filter { it.userId == bill.userId && canPayBills(it) }
        val matches = candidates.mapNotNull { p -> match(bill, p, onlyCardBill = false)?.let { p to it } }
        val best = matches.sortedWith(compareByDescending<Pair<Transaction, BillMatch>> { it.second.evidence.strength }
            .thenBy { amountGap(bill, it.first.amount) }).firstOrNull() ?: return null
        val runnerUp = matches.getOrNull(1)
        if (runnerUp != null && runnerUp.second.evidence == best.second.evidence && amountGap(bill, runnerUp.first.amount) == amountGap(bill, best.first.amount)) return null
        return best.first
    }

    /** The window a payment for [bill] must fall in (local dates, inclusive). */
    fun window(bill: Bill): ClosedRange<LocalDate> {
        val start = bill.billingPeriodEnd?.takeIf { it.isBefore(bill.dueDate) } ?: bill.dueDate.minusDays(WINDOW_BEFORE_DUE_DAYS)
        return start..bill.dueDate.plusDays(GRACE_AFTER_DUE_DAYS)
    }

    private fun match(bill: Bill, payment: Transaction, onlyCardBill: Boolean): BillMatch? {
        val coverage = coverage(bill, payment.amount) ?: return null
        if (!inWindow(bill, payment)) return null
        val evidence = evidence(bill, payment, onlyCardBill) ?: return null
        return BillMatch(bill, coverage, evidence)
    }

    internal fun coverage(bill: Bill, amount: BigDecimal): PaymentCoverage? = when {
        bill.amount.signum() > 0 && BillTrackingService.isAmountWithinTolerance(bill.amount, amount, FULL_TOLERANCE) -> PaymentCoverage.FULL
        bill.minimumDue != null && bill.minimumDue.signum() > 0 && BillTrackingService.isAmountWithinTolerance(bill.minimumDue, amount, MINIMUM_TOLERANCE) -> PaymentCoverage.MINIMUM_DUE
        else -> null
    }

    private fun inWindow(bill: Bill, payment: Transaction) = payment.transactedAt.atZone(ZONE).toLocalDate() in window(bill)

    private fun evidence(bill: Bill, payment: Transaction, onlyCardBill: Boolean): BillEvidence? {
        val text = listOfNotNull(payment.merchantRaw, payment.merchantName, payment.upiVpa).joinToString(" ")
        val last4 = bill.accountRef?.filter(Char::isDigit)?.takeLast(4)?.takeIf { it.length == 4 }
        if (last4 != null && Regex("(?<!\\d)$last4(?!\\d)").containsMatchIn(text)) return BillEvidence.ACCOUNT_NUMBER
        val names = listOfNotNull(payment.merchantName, payment.merchantRaw).filter { it.isNotBlank() }
        if (names.any { BillTrackingService.fuzzyMatch(bill.billerName, it) > NAME_SIMILARITY }) return BillEvidence.BILLER_NAME
        if (onlyCardBill && bill.billType == BillType.CREDIT_CARD && payment.subcategory == CARD_BILL_TRANSFER) return BillEvidence.ONLY_CARD_BILL
        return null
    }

    private fun pickUnambiguous(matches: List<BillMatch>, amount: BigDecimal): BillMatch? {
        val ranked = matches.sortedWith(compareByDescending<BillMatch> { it.evidence.strength }
            .thenBy { amountGap(it.bill, amount) }
            .thenBy { it.bill.dueDate })
        val best = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1)
        val tie = runnerUp != null && runnerUp.evidence == best.evidence && amountGap(runnerUp.bill, amount) == amountGap(best.bill, amount) && runnerUp.bill.dueDate == best.bill.dueDate
        return if (tie) null else best
    }

    /** How far [amount] is from what the bill asks (the closer of the full amount and the minimum due). */
    private fun amountGap(bill: Bill, amount: BigDecimal): BigDecimal =
        listOfNotNull(bill.amount, bill.minimumDue).minOf { (it - amount).abs() }

    /** Pipeline subcategory for a credit-card bill payment (TransferStep, AOS-208). */
    const val CARD_BILL_TRANSFER = "credit_card_bill"
    /** Pipeline subcategory for a pay-later repayment (TransferStep, AOS-909). */
    const val PAY_LATER_BILL_TRANSFER = "pay_later_bill"
    private val BILL_TRANSFERS = setOf(CARD_BILL_TRANSFER, PAY_LATER_BILL_TRANSFER)
    private val PAYABLE = setOf(BillStatus.UPCOMING, BillStatus.DUE, BillStatus.OVERDUE)
}
