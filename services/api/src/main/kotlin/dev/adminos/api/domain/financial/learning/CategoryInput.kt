package dev.adminos.api.domain.financial.learning

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionNature
import java.math.BigDecimal

/**
 * What the learned categoriser looks at for one payment (AOS-707): the payee as the pipeline cleaned it, the
 * bank's narration, the UPI ID, the payment method, what it counts as, and the amount. Never an identity,
 * an account number or a message body. Everything stays inside the API (D-67: local-first).
 */
data class CategoryInput(
    val merchantName: String?,
    val merchantRaw: String? = null,
    val upiVpa: String? = null,
    val paymentMethod: PaymentMethod? = null,
    val nature: TransactionNature = TransactionNature.SPEND,
    val amount: BigDecimal? = null
) {
    init {
        require(amount == null || amount.signum() >= 0) { "An amount is never negative" }
    }

    /** True when there is nothing that names the payee, so a guess would rest on the method and amount alone. */
    val isAnonymous: Boolean get() = merchantName.isNullOrBlank() && merchantRaw.isNullOrBlank() && upiVpa.isNullOrBlank()

    companion object {
        fun of(t: Transaction) = CategoryInput(
            merchantName = t.merchantName,
            merchantRaw = t.merchantRaw,
            upiVpa = t.upiVpa,
            paymentMethod = t.paymentMethod,
            nature = t.nature,
            amount = t.amount
        )
    }
}
