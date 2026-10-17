package dev.adminos.api.domain.financial.receipts

import dev.adminos.api.domain.common.TimeRange
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.receipts.ReceiptFixtures.hash
import dev.adminos.api.domain.financial.receipts.ReceiptFixtures.receipt
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-903: hidden fees per app and month, for the "Where it leaks" page (AOS-902). */
class HiddenFeesServiceTest {
    private val user = UUID.randomUUID()
    private val ist = ZoneId.of("Asia/Kolkata")
    private val receipts = InMemoryOrderReceiptRepository()
    private val service = HiddenFeesService(receipts)
    private val october = MonthSpan(YearMonth.of(2026, 10), YearMonth.of(2026, 10))

    @Test
    fun `fees are totalled per app, tips apart, with the fee share and the receipts behind them`() = runBlocking<Unit> {
        val a = receipts.save(receipt(user, total = "487.00", fees = mapOf(FeeKind.DELIVERY to "35.00", FeeKind.PLATFORM to "10.00", FeeKind.TIP to "20.00")))
        val b = receipts.save(receipt(user, total = "300.00", fees = mapOf(FeeKind.DELIVERY to "25.00", FeeKind.SURGE to "15.00"), items = 2))
        val txn = UUID.randomUUID()
        receipts.updateMatch(a.id, ReceiptMatch(ReceiptMatchState.MATCHED, txn, 12, "x", MatchedBy.RULES), emptySet())
        val zepto = receipts.save(receipt(user, app = "Zepto", total = "213.00", vertical = ReceiptVertical.QUICK_COMMERCE, items = null,
            fees = mapOf(FeeKind.HANDLING to "13.00", FeeKind.SMALL_CART to "35.00"), mode = ReceiptPaymentMode.PAY_LATER))
        receipts.updateMatch(zepto.id, ReceiptMatch.NO_PAYMENT, emptySet())

        val month = service.feesByMonth(user, october, ist).single()
        assertEquals(YearMonth.of(2026, 10), month.month)
        val (swiggy, zeptoFees) = month.apps
        assertEquals("Swiggy", swiggy.app, "the app with the most hidden fees comes first")
        assertEquals(ReceiptVertical.FOOD_DELIVERY, swiggy.vertical)
        assertEquals(2, swiggy.orders)
        assertEquals(5, swiggy.items)
        assertEquals(BigDecimal("787.00"), swiggy.orderTotal)
        assertEquals(BigDecimal("85.00"), swiggy.hiddenFees)
        assertEquals(BigDecimal("20.00"), swiggy.tips)
        assertEquals(BigDecimal("0.1080"), swiggy.feeShare)
        assertEquals(listOf(FeeLine(FeeKind.DELIVERY, BigDecimal("60.00"), 2), FeeLine(FeeKind.TIP, BigDecimal("20.00"), 1),
            FeeLine(FeeKind.SURGE, BigDecimal("15.00"), 1), FeeLine(FeeKind.PLATFORM, BigDecimal("10.00"), 1)), swiggy.fees)
        assertEquals(1, swiggy.matchedOrders)
        assertEquals(1, swiggy.unmatchedOrders)
        assertEquals(listOf(txn), swiggy.transactionIds)
        assertEquals(setOf(a.id, b.id), swiggy.receiptIds.toSet())
        assertEquals(1, zeptoFees.noPaymentOrders)
        assertEquals(0, zeptoFees.items, "an unknown item count adds nothing")
        assertEquals(BigDecimal("48.00"), zeptoFees.hiddenFees)
        with(month.totals) {
            assertEquals(3, orders)
            assertEquals(BigDecimal("1000.00"), orderTotal)
            assertEquals(BigDecimal("133.00"), hiddenFees)
            assertEquals(BigDecimal("0.1330"), feeShare)
        }
    }

    @Test
    fun `cancelled orders and orders refunded in full add no fees, a partial refund keeps them`() = runBlocking<Unit> {
        receipts.save(receipt(user, status = ReceiptStatus.CANCELLED))
        receipts.save(receipt(user, total = "200.00", refunds = listOf("150.00", "50.00")))
        receipts.save(receipt(user, total = "300.00", refunds = listOf("120.00"), fees = mapOf(FeeKind.DELIVERY to "30.00")))
        receipts.save(receipt(user, total = null, status = ReceiptStatus.CANCELLED)) // only the cancellation email was seen
        val swiggy = service.feesByMonth(user, october, ist).single().apps.single()
        assertEquals(1, swiggy.orders)
        assertEquals(2, swiggy.cancelledOrders)
        assertEquals(BigDecimal("30.00"), swiggy.hiddenFees)
        assertEquals(4, swiggy.receiptIds.size, "every receipt stays traceable")
    }

    @Test
    fun `months are the user's own, and empty months are listed`() = runBlocking<Unit> {
        // 23:50 IST on 31 October is 18:20 UTC: October in India.
        receipts.save(receipt(user, at = Instant.parse("2026-10-31T18:20:00Z")))
        // 00:10 IST on 1 November is still 31 October in UTC: November in India.
        receipts.save(receipt(user, at = Instant.parse("2026-10-31T18:40:00Z")))
        val months = service.feesByMonth(user, MonthSpan(YearMonth.of(2026, 9), YearMonth.of(2026, 11)), ist)
        assertEquals(listOf(0, 1, 1), months.map { it.totals.orders })
        assertEquals(listOf("2026-09", "2026-10", "2026-11"), months.map { it.month.toString() })
        assertEquals(BigDecimal("0.0000"), months.first().totals.feeShare)
        val utc = service.feesByMonth(user, MonthSpan(YearMonth.of(2026, 10), YearMonth.of(2026, 11)), ZoneId.of("UTC"))
        assertEquals(listOf(2, 0), utc.map { it.totals.orders })
    }

    @Test
    fun `another user's receipts are never counted`() = runBlocking<Unit> {
        receipts.save(receipt(UUID.randomUUID()))
        assertTrue(service.feesByMonth(user, october, ist).single().apps.isEmpty())
        assertTrue(service.receiptsIn(user, YearMonth.of(2026, 10), ist, null).isEmpty())
    }

    @Test
    fun `the receipts of a month, for one app or all`() = runBlocking<Unit> {
        receipts.save(receipt(user))
        receipts.save(receipt(user, app = "Zepto"))
        assertEquals(2, service.receiptsIn(user, YearMonth.of(2026, 10), ist, null).size)
        assertEquals(listOf("Zepto"), service.receiptsIn(user, YearMonth.of(2026, 10), ist, "zepto").map { it.app })
    }

    @Test
    fun `a month span is 1 to 12 months, in order`() {
        assertEquals(12, MonthSpan(YearMonth.of(2026, 1), YearMonth.of(2026, 12)).months.size)
        assertFailsWith<IllegalArgumentException> { MonthSpan(YearMonth.of(2026, 1), YearMonth.of(2027, 1)) }
        assertFailsWith<IllegalArgumentException> { MonthSpan(YearMonth.of(2026, 2), YearMonth.of(2026, 1)) }
        val r = october.range(ist)
        assertEquals(TimeRange(Instant.parse("2026-09-30T18:30:00Z"), Instant.parse("2026-10-31T18:30:00Z")), r)
        assertTrue(Instant.parse("2026-10-31T18:29:59Z") in r)
        assertFalse(Instant.parse("2026-10-31T18:30:00Z") in r)
        assertFailsWith<IllegalArgumentException> { TimeRange(r.until, r.from) }
    }

    @Test
    fun `a receipt validates itself and knows what it expects to be paid`() {
        val ok = receipt(user, total = "450.00", paid = "350.00", mode = ReceiptPaymentMode.SPLIT)
        assertEquals(listOf(BigDecimal("350.00")), ok.expectedPaymentAmounts())
        assertEquals(listOf(BigDecimal("450.00")), receipt(user, total = "450.00", paid = "450.0").expectedPaymentAmounts().map { it.setScale(2) })
        assertEquals(listOf(BigDecimal("400.00"), BigDecimal("450.00")), receipt(user, total = "450.00", paid = "400.00").expectedPaymentAmounts())
        assertEquals(emptyList(), receipt(user, total = null).expectedPaymentAmounts())
        val bad: List<() -> OrderReceipt> = listOf(
            { receipt(user, app = " ") },
            { receipt(user, app = "x".repeat(41)) },
            { receipt(user, total = "0") },
            { receipt(user, paid = "-1") },
            { receipt(user, items = 0) },
            { receipt(user, fees = mapOf(FeeKind.TIP to "0")) },
            { receipt(user, refunds = listOf("0")) },
            { receipt(user).copy(orderRefHash = "not-a-hash") },
            { receipt(user).copy(sourceRef = "") }
        )
        bad.forEachIndexed { i, make -> assertFailsWith<IllegalArgumentException>("case $i") { make() } }
        assertFailsWith<IllegalArgumentException> { ReceiptMatch(ReceiptMatchState.MATCHED) }
        assertFailsWith<IllegalArgumentException> { ReceiptMatch(ReceiptMatchState.UNMATCHED, UUID.randomUUID()) }
        assertFailsWith<IllegalArgumentException> { ReceiptMatch(ReceiptMatchState.NO_PAYMENT, reason = "x".repeat(201)) }
        assertEquals(64, hash("x").length)
    }

    @Test
    fun `payment modes say whether a bank payment is expected and which method`() {
        assertEquals(setOf(ReceiptPaymentMode.WALLET, ReceiptPaymentMode.PAY_LATER, ReceiptPaymentMode.COD),
            ReceiptPaymentMode.entries.filterNot { it.expectsBankPayment }.toSet())
        assertEquals(PaymentMethod.UPI, ReceiptPaymentMode.UPI.paymentMethod)
        assertEquals(PaymentMethod.CARD_CREDIT, ReceiptPaymentMode.CARD.paymentMethod)
        assertEquals(PaymentMethod.NETBANKING, ReceiptPaymentMode.NETBANKING.paymentMethod)
        assertEquals(null, ReceiptPaymentMode.SPLIT.paymentMethod)
        assertEquals(listOf(FeeKind.TIP), FeeKind.entries.filterNot { it.isHidden })
    }
}
