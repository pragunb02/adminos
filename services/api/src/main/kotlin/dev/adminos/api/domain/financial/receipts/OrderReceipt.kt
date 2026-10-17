package dev.adminos.api.domain.financial.receipts

import dev.adminos.api.domain.common.sumMoney
import dev.adminos.api.domain.financial.PaymentMethod
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** The kind of app (AOS-903), for the habit lenses (AOS-901). */
enum class ReceiptVertical { QUICK_COMMERCE, FOOD_DELIVERY, ECOMMERCE }

/** A charge on top of the items. Same closed set as the Go parsers and the V50 CHECK. */
enum class FeeKind {
    DELIVERY, HANDLING, SMALL_CART, PLATFORM, SURGE, PACKAGING, TIP;

    /** A tip is the user's choice; every other kind is a fee the app adds ("hidden fees"). */
    val isHidden: Boolean get() = this != TIP
}

enum class ReceiptStatus { PLACED, CANCELLED }

/** How the receipt says the order was paid. */
enum class ReceiptPaymentMode {
    UPI, CARD, NETBANKING, WALLET, PAY_LATER, COD, SPLIT, UNKNOWN;

    /** Wallet, pay-later and cash on delivery leave no bank record at order time. */
    val expectsBankPayment: Boolean get() = this != WALLET && this != PAY_LATER && this != COD

    /** The transaction's payment method this mode implies, for the matcher's "same method" point. */
    val paymentMethod: PaymentMethod?
        get() = when (this) {
            UPI -> PaymentMethod.UPI
            CARD -> PaymentMethod.CARD_CREDIT
            NETBANKING -> PaymentMethod.NETBANKING
            else -> null
        }
}

enum class ReceiptMatchState { UNMATCHED, MATCHED, SUGGESTED, NO_PAYMENT }

enum class MatchedBy { RULES, USER }

/** How a receipt is tied to its payment. */
data class ReceiptMatch(
    val state: ReceiptMatchState,
    val transactionId: UUID? = null,
    val score: Int? = null,
    val reason: String? = null,
    val matchedBy: MatchedBy? = null
) {
    init {
        val linked = state == ReceiptMatchState.MATCHED || state == ReceiptMatchState.SUGGESTED
        require(linked == (transactionId != null)) { "a matched or suggested receipt has a transaction, and only those do" }
        require(reason == null || reason.length <= 200) { "a match reason is at most 200 characters" }
    }

    companion object {
        val UNMATCHED = ReceiptMatch(ReceiptMatchState.UNMATCHED)
        val NO_PAYMENT = ReceiptMatch(ReceiptMatchState.NO_PAYMENT, reason = "paid without a bank payment (wallet, pay-later or cash)", matchedBy = MatchedBy.RULES)
    }
}

/**
 * One order from an app's receipt emails (AOS-903, D-88). Every email about the order is merged into
 * it by the Gmail worker. Structured fields only (D-33): the order number is a hash, and no item
 * names or addresses exist here.
 */
data class OrderReceipt(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    /** The merchant-catalogue brand, e.g. "Swiggy Instamart". */
    val app: String,
    val vertical: ReceiptVertical,
    val orderRefHash: String,
    /** The Gmail message ID of the first email about the order. */
    val sourceRef: String,
    val orderedAt: Instant,
    val status: ReceiptStatus = ReceiptStatus.PLACED,
    /** Null when only a cancellation or refund email has been seen so far. */
    val orderTotal: BigDecimal? = null,
    val itemCount: Int? = null,
    val paymentMode: ReceiptPaymentMode = ReceiptPaymentMode.UNKNOWN,
    /** What reached the bank, card or UPI, when the receipt shows it. */
    val paidAmount: BigDecimal? = null,
    val fees: Map<FeeKind, BigDecimal> = emptyMap(),
    /** Refund amounts announced for the order (the same amount twice counts once). */
    val refunds: List<BigDecimal> = emptyList(),
    val match: ReceiptMatch = ReceiptMatch.UNMATCHED,
    /** Payments the user said are not this order's; never proposed again. */
    val rejectedTransactionIds: Set<UUID> = emptySet(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {
    init {
        require(app.isNotBlank() && app.length <= 40) { "app must be 1..40 characters" }
        require(orderRefHash.matches(Regex("[0-9a-f]{64}"))) { "orderRefHash must be a SHA-256 hex digest" }
        require(sourceRef.isNotBlank() && sourceRef.length <= 255) { "sourceRef must be 1..255 characters" }
        require(orderTotal == null || orderTotal.signum() > 0) { "orderTotal must be positive" }
        require(paidAmount == null || paidAmount.signum() > 0) { "paidAmount must be positive" }
        require(itemCount == null || itemCount > 0) { "itemCount must be positive" }
        require(fees.values.all { it.signum() > 0 }) { "fees must be positive" }
        require(refunds.all { it.signum() > 0 }) { "refunds must be positive" }
    }

    val refunded: BigDecimal get() = refunds.sumMoney()

    /** Whether the order counts in the fee totals: placed, with a total, and not refunded in full. */
    val counts: Boolean get() = status == ReceiptStatus.PLACED && orderTotal != null && refunded < orderTotal

    val hiddenFees: BigDecimal get() = fees.filterKeys { it.isHidden }.values.sumMoney()
    val tips: BigDecimal get() = fees[FeeKind.TIP] ?: BigDecimal.ZERO

    /**
     * The amounts a bank payment for this order would have, preferred first: what reached the bank,
     * then the total. A split payment only has its bank part (the total includes the wallet's).
     */
    fun expectedPaymentAmounts(): List<BigDecimal> =
        if (paymentMode == ReceiptPaymentMode.SPLIT) listOfNotNull(paidAmount)
        else listOfNotNull(paidAmount, orderTotal).distinctBy { it.stripTrailingZeros() }
}
