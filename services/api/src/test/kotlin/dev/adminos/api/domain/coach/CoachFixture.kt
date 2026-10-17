package dev.adminos.api.domain.coach

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** Synthetic transactions for the coach tests (realistic Indian merchants and UPI IDs; no real data). */
object CoachFixture {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    val USER: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c0ac")

    fun txn(
        merchant: String?,
        amount: String = "100.00",
        at: LocalDateTime = LocalDateTime.of(2026, 10, 14, 13, 0),
        nature: TransactionNature = TransactionNature.SPEND,
        category: TransactionCategory = TransactionCategory.OTHER,
        subcategory: String? = null,
        vpa: String? = null,
        user: UUID = USER,
        zone: ZoneId = IST,
        source: String = "sms"
    ) = Transaction(
        userId = user, sourceType = source, amount = BigDecimal(amount), nature = nature, merchantName = merchant,
        type = when (nature) {
            TransactionNature.SPEND -> TransactionType.DEBIT
            TransactionNature.TRANSFER -> TransactionType.TRANSFER
            TransactionNature.REFUND -> TransactionType.REFUND
            TransactionNature.REVERSAL -> TransactionType.REVERSAL
            TransactionNature.INCOME -> TransactionType.CREDIT
        },
        category = category, subcategory = subcategory, upiVpa = vpa,
        paymentMethod = if (vpa != null) PaymentMethod.UPI else PaymentMethod.CARD_DEBIT,
        transactedAt = at.atZone(zone).toInstant()
    )
}
