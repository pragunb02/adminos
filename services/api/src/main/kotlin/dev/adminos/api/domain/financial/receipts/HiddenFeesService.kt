package dev.adminos.api.domain.financial.receipts

import dev.adminos.api.domain.common.TimeRange
import dev.adminos.api.domain.common.sumMoney
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/** A span of whole months, [first]..[last] inclusive. */
data class MonthSpan(val first: YearMonth, val last: YearMonth) {
    init {
        require(!last.isBefore(first)) { "to must not be before from" }
        require(first.plusMonths(MAX_MONTHS - 1L) >= last) { "at most $MAX_MONTHS months" }
    }

    val months: List<YearMonth> get() = generateSequence(first) { it.plusMonths(1) }.takeWhile { !it.isAfter(last) }.toList()

    fun range(zone: ZoneId) = TimeRange(first.atDay(1).atStartOfDay(zone).toInstant(), last.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant())

    companion object {
        const val MAX_MONTHS = 12
    }
}

data class FeeLine(val kind: FeeKind, val amount: BigDecimal, val orders: Int)

/** One app's orders in a month: what the fees came to, and the receipts and payments behind them. */
data class AppFees(
    val app: String,
    val vertical: ReceiptVertical,
    val orders: Int,
    val cancelledOrders: Int,
    val items: Int,
    val orderTotal: BigDecimal,
    val hiddenFees: BigDecimal,
    val tips: BigDecimal,
    val fees: List<FeeLine>,
    val matchedOrders: Int,
    val unmatchedOrders: Int,
    /** Paid by wallet, pay-later or cash on delivery: no bank payment to match. */
    val noPaymentOrders: Int,
    val receiptIds: List<UUID>,
    val transactionIds: List<UUID>
) {
    val feeShare: BigDecimal get() = share(hiddenFees, orderTotal)
}

data class FeeTotals(val orders: Int, val cancelledOrders: Int, val items: Int, val orderTotal: BigDecimal, val hiddenFees: BigDecimal, val tips: BigDecimal) {
    val feeShare: BigDecimal get() = share(hiddenFees, orderTotal)
}

data class MonthFees(val month: YearMonth, val totals: FeeTotals, val apps: List<AppFees>)

/** hidden fees ÷ order total, 4 places; 0 when there is no total. */
internal fun share(part: BigDecimal, whole: BigDecimal): BigDecimal =
    if (whole.signum() == 0) BigDecimal.ZERO.setScale(4) else part.divide(whole, 4, RoundingMode.HALF_UP)

/**
 * Hidden fees per app and month from order receipts (AOS-903), for the "Where it leaks" page (AOS-902).
 * Deterministic and traceable (D-67, D-80): every figure is a sum over the listed receipts.
 * Months are the user's own (their time zone). Cancelled orders and orders refunded in full are
 * counted as cancelled and add no fees.
 */
class HiddenFeesService(private val receipts: OrderReceiptRepository) {

    suspend fun feesByMonth(userId: UUID, span: MonthSpan, zone: ZoneId): List<MonthFees> {
        val all = receipts.findByUser(userId, span.range(zone))
        val byMonth = all.groupBy { YearMonth.from(it.orderedAt.atZone(zone)) }
        return span.months.map { month -> monthFees(month, byMonth[month].orEmpty()) }
    }

    /** One month's receipts, listed by app (for traceability), optionally one app's only. */
    suspend fun receiptsIn(userId: UUID, month: YearMonth, zone: ZoneId, app: String?): List<OrderReceipt> =
        receipts.findByUser(userId, MonthSpan(month, month).range(zone))
            .filter { app == null || it.app.equals(app, ignoreCase = true) }

    private fun monthFees(month: YearMonth, list: List<OrderReceipt>): MonthFees {
        val apps = list.groupBy { it.app }.map { (app, rs) -> appFees(app, rs) }
            .sortedWith(compareByDescending<AppFees> { it.hiddenFees }.thenBy { it.app })
        val totals = FeeTotals(
            orders = apps.sumOf { it.orders }, cancelledOrders = apps.sumOf { it.cancelledOrders }, items = apps.sumOf { it.items },
            orderTotal = apps.map { it.orderTotal }.sumMoney(), hiddenFees = apps.map { it.hiddenFees }.sumMoney(), tips = apps.map { it.tips }.sumMoney()
        )
        return MonthFees(month, totals, apps)
    }

    private fun appFees(app: String, all: List<OrderReceipt>): AppFees {
        val counted = all.filter { it.counts }
        val fees = FeeKind.entries.mapNotNull { kind ->
            val charged = counted.mapNotNull { it.fees[kind] }
            if (charged.isEmpty()) null else FeeLine(kind, charged.sumMoney(), charged.size)
        }.sortedWith(compareByDescending<FeeLine> { it.amount }.thenBy { it.kind })
        val withTotal = all.filter { it.orderTotal != null }
        return AppFees(
            app = app,
            vertical = all.first().vertical, // one parser per app decides it
            orders = counted.size,
            cancelledOrders = withTotal.size - counted.size,
            items = counted.sumOf { it.itemCount ?: 0 },
            orderTotal = counted.mapNotNull { it.orderTotal }.sumMoney(),
            hiddenFees = counted.map { it.hiddenFees }.sumMoney(),
            tips = counted.map { it.tips }.sumMoney(),
            fees = fees,
            matchedOrders = counted.count { it.match.state == ReceiptMatchState.MATCHED },
            unmatchedOrders = counted.count { it.match.state == ReceiptMatchState.UNMATCHED || it.match.state == ReceiptMatchState.SUGGESTED },
            noPaymentOrders = counted.count { it.match.state == ReceiptMatchState.NO_PAYMENT },
            receiptIds = all.map { it.id },
            transactionIds = all.filter { it.match.state == ReceiptMatchState.MATCHED }.mapNotNull { it.match.transactionId }
        )
    }
}
