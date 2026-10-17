package dev.adminos.api.domain.financial.receipts

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionType
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Synthetic receipts and payments for the AOS-903 tests (no real orders). */
object ReceiptFixtures {
    val ORDER_AT: Instant = Instant.parse("2026-10-17T16:10:00Z") // 21:40 IST

    fun hash(seed: String): String = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()).joinToString("") { "%02x".format(it) }

    fun receipt(
        user: UUID, app: String = "Swiggy", total: String? = "487.00", at: Instant = ORDER_AT,
        mode: ReceiptPaymentMode = ReceiptPaymentMode.UPI, paid: String? = null,
        fees: Map<FeeKind, String> = mapOf(FeeKind.DELIVERY to "35.00", FeeKind.PLATFORM to "10.00", FeeKind.TIP to "20.00"),
        status: ReceiptStatus = ReceiptStatus.PLACED, items: Int? = 3, refunds: List<String> = emptyList(),
        vertical: ReceiptVertical = ReceiptVertical.FOOD_DELIVERY, order: String = UUID.randomUUID().toString()
    ) = OrderReceipt(
        userId = user, app = app, vertical = vertical, orderRefHash = hash("$app:$order"), sourceRef = "msg-$order", orderedAt = at,
        status = status, orderTotal = total?.let(::BigDecimal), itemCount = items, paymentMode = mode, paidAmount = paid?.let(::BigDecimal),
        fees = fees.mapValues { BigDecimal(it.value) }, refunds = refunds.map(::BigDecimal)
    )

    fun payment(
        user: UUID, amount: String = "487.00", at: Instant = ORDER_AT.plusSeconds(45), merchant: String? = "Swiggy",
        method: PaymentMethod? = PaymentMethod.UPI, source: String = "sms"
    ) = Transaction(userId = user, sourceType = source, type = TransactionType.DEBIT, amount = BigDecimal(amount), merchantName = merchant,
        paymentMethod = method, transactedAt = at)
}
