package dev.adminos.api.domain.financial.paylater

import dev.adminos.api.domain.common.sumMoney
import dev.adminos.api.domain.financial.Bill
import dev.adminos.api.domain.financial.BillRepository
import dev.adminos.api.domain.financial.BillStatus
import dev.adminos.api.domain.financial.BillType
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionRepository
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.ingestion.sms.PayLaterSmsParser
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A provider's bill next to the pay-later purchases recorded in its period (AOS-909). A repayment is a
 * transfer (the purchases are the spending), so a purchase that left no message is missing from the
 * totals: [unrecorded] makes that gap visible in Review instead of guessing what it bought.
 */
data class BillGap(val bill: Bill, val recorded: BigDecimal, val unrecorded: BigDecimal)

/** What one provider is owed (AOS-909). */
data class ProviderDebt(
    val provider: String,
    /** Unpaid bills plus purchases since the latest bill. */
    val outstanding: BigDecimal,
    val unpaidBills: List<Bill>,
    /** Pay-later purchases after the latest bill: owed, not billed yet. */
    val unbilledPurchases: List<Transaction>,
    val nextDue: LocalDate?,
    /** Recorded purchases in the last 30 days, for the coach's habit view. */
    val purchasesLast30Days: BigDecimal,
    /** Recent bills larger than the purchases recorded for them. */
    val gaps: List<BillGap> = emptyList()
)

data class PayLaterSummary(val outstanding: BigDecimal, val providers: List<ProviderDebt>)

/**
 * Pay-later debt outstanding per provider (AOS-909, D-89), from the user's own bills and tagged
 * purchases only: deterministic and traceable (D-67). A purchase message names its provider
 * (`metadata.payLater`); one without a known provider is grouped as "Pay later".
 */
class PayLaterService(private val bills: BillRepository, private val transactions: TransactionRepository) {

    suspend fun summary(userId: UUID, now: Instant): PayLaterSummary {
        val providerBills = bills.findByUserId(userId, limit = MAX_ROWS)
            .filter { it.billType == BillType.PAY_LATER && it.status != BillStatus.CANCELLED }
        val purchases = transactions.findByUserId(userId, from = now.minus(LOOK_BACK), to = now, type = TransactionType.DEBIT,
            nature = TransactionNature.SPEND, limit = MAX_ROWS).filter { it.paymentMethod == PaymentMethod.PAY_LATER }
        val names = (providerBills.map { canonical(it.billerName) } + purchases.map(::providerOf)).distinct()
        val debts = names.map { name -> debt(name, providerBills.filter { canonical(it.billerName) == name }, purchases.filter { providerOf(it) == name }, now) }
            .filter { it.outstanding.signum() > 0 || it.purchasesLast30Days.signum() > 0 || it.gaps.isNotEmpty() }
            .sortedWith(compareByDescending<ProviderDebt> { it.outstanding }.thenBy { it.provider })
        return PayLaterSummary(debts.map { it.outstanding }.sumMoney(), debts)
    }

    private fun debt(provider: String, providerBills: List<Bill>, purchases: List<Transaction>, now: Instant): ProviderDebt {
        val unpaid = providerBills.filter { it.status in UNPAID }.sortedBy { it.dueDate }
        val lastBill = providerBills.maxOfOrNull { it.createdAt }
        val unbilled = purchases.filter { lastBill == null || it.transactedAt.isAfter(lastBill) }.sortedBy { it.transactedAt }
        return ProviderDebt(
            provider = provider,
            outstanding = (unpaid.map { it.amount } + unbilled.map { it.amount }).sumMoney(),
            unpaidBills = unpaid,
            unbilledPurchases = unbilled,
            nextDue = unpaid.firstOrNull()?.dueDate,
            purchasesLast30Days = purchases.filter { !it.transactedAt.isBefore(now.minus(Duration.ofDays(30))) }.map { it.amount }.sumMoney(),
            gaps = gaps(providerBills, purchases, now)
        )
    }

    /**
     * Each bill seen in the last [LOOK_BACK] covers the purchases since the provider's previous bill (or the
     * month before it): when the bill is larger, the difference was spent without a message we could read.
     */
    private fun gaps(providerBills: List<Bill>, purchases: List<Transaction>, now: Instant): List<BillGap> {
        val ordered = providerBills.sortedBy { it.createdAt }
        return ordered.mapIndexedNotNull { i, bill ->
            if (bill.createdAt.isBefore(now.minus(LOOK_BACK))) return@mapIndexedNotNull null
            val from = ordered.getOrNull(i - 1)?.createdAt ?: bill.createdAt.minus(FIRST_PERIOD)
            val recorded = purchases.filter { it.transactedAt.isAfter(from) && !it.transactedAt.isAfter(bill.createdAt) }.map { it.amount }.sumMoney()
            val missing = bill.amount - recorded
            if (missing.signum() > 0) BillGap(bill, recorded, missing) else null
        }.sortedByDescending { it.bill.dueDate }
    }

    private fun providerOf(t: Transaction): String =
        (t.metadata?.get(PayLaterSmsParser.PAY_LATER_MARKER) as? String)?.let(::canonical) ?: UNKNOWN_PROVIDER

    /** One spelling per provider ("lazypay" from an email is LazyPay). */
    private fun canonical(name: String): String = PayLaterProviders.byName(name)?.name ?: PayLaterProviders.namedIn(name)?.name ?: name.trim()

    companion object {
        const val UNKNOWN_PROVIDER = "Pay later"
        private val UNPAID = setOf(BillStatus.UPCOMING, BillStatus.DUE, BillStatus.OVERDUE)
        /** Unbilled purchases older than this are assumed billed (bills come at least monthly). */
        private val LOOK_BACK: Duration = Duration.ofDays(120)
        private const val MAX_ROWS = 1000
        /** A provider's first bill we see covers about a month of purchases. */
        private val FIRST_PERIOD: Duration = Duration.ofDays(31)
    }
}
