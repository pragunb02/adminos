package dev.adminos.api.domain.financial

import dev.adminos.api.domain.common.toMoney
import kotlinx.serialization.Serializable

// ── Request DTOs ──

@Serializable
data class CategoryOverrideRequest(
    val category: String,
    val subcategory: String? = null,
    /** Remember for this payee: future transactions and past ones not set by hand (AOS-210). */
    val remember: Boolean = true
)

// ── Response DTOs ──

@Serializable
data class TransactionResponse(
    val id: String,
    val type: String,
    /** spend / income / transfer / refund / reversal (D-39): what it counts as in totals. */
    val nature: String,
    /** Exact decimal string, e.g. "349.00" (D-30). */
    val amount: String,
    val currency: String,
    val merchantName: String?,
    val category: String,
    val subcategory: String?,
    val categorySource: String,
    val sourceType: String,
    val accountLast4: String?,
    val paymentMethod: String?,
    val isRecurring: Boolean,
    val isAnomaly: Boolean,
    val transactedAt: String
) {
    companion object {
        fun from(t: Transaction) = TransactionResponse(
            id = t.id.toString(),
            type = t.type.name.lowercase(),
            nature = t.nature.name.lowercase(),
            amount = t.amount.toMoney(),
            currency = t.currency,
            merchantName = t.merchantName,
            category = t.category.name.lowercase(),
            subcategory = t.subcategory,
            categorySource = t.categorySource,
            sourceType = t.sourceType,
            accountLast4 = t.accountLast4,
            paymentMethod = t.paymentMethod?.name?.lowercase(),
            isRecurring = t.isRecurring,
            isAnomaly = t.isAnomaly,
            transactedAt = t.transactedAt.toString()
        )
    }
}

@Serializable
data class TransactionSummaryResponse(
    val period: PeriodInfo,
    val totalSpending: String,
    val byCategory: Map<String, String>
)

@Serializable
data class PeriodInfo(
    val from: String,
    val to: String
)

@Serializable
data class AccountResponse(
    val id: String,
    val bankName: String?,
    val bankCode: String?,
    val accountType: String,
    val accountLast4: String,
    val accountName: String?,
    val isPrimary: Boolean,
    val currency: String,
    val createdAt: String
) {
    companion object {
        fun from(a: Account) = AccountResponse(
            id = a.id.toString(),
            bankName = a.bankName,
            bankCode = a.bankCode,
            accountType = a.accountType.name.lowercase(),
            accountLast4 = a.accountLast4,
            accountName = a.accountName,
            isPrimary = a.isPrimary,
            currency = a.currency,
            createdAt = a.createdAt.toString()
        )
    }
}

@Serializable
data class SubscriptionResponse(
    val id: String,
    val name: String,
    val merchantName: String?,
    val category: String,
    val amount: String,
    val currency: String,
    val billingCycle: String,
    val nextBillingDate: String?,
    val lastBilledDate: String?,
    val firstBilledDate: String?,
    val status: String,
    val priceChanged: Boolean,
    val priceChangePct: Double?,
    val isFlagged: Boolean,
    val flaggedReason: String?,
    /** 0.00–1.00, null until first scored (AOS-509); ≥ 0.70 flags it. */
    val wasteScore: Double?,
    /** Why the score is what it is, strongest first; may end with "Usage unknown…". */
    val wasteReasons: List<String>,
    val transactionIds: List<String>,
    val createdAt: String
) {
    companion object {
        fun from(s: Subscription) = SubscriptionResponse(
            id = s.id.toString(),
            name = s.name,
            merchantName = s.merchantName,
            category = s.category.name.lowercase(),
            amount = s.amount.toMoney(),
            currency = s.currency,
            billingCycle = s.billingCycle.name.lowercase(),
            nextBillingDate = s.nextBillingDate?.toString(),
            lastBilledDate = s.lastBilledDate?.toString(),
            firstBilledDate = s.firstBilledDate?.toString(),
            status = s.status.name.lowercase(),
            priceChanged = s.priceChanged,
            priceChangePct = s.priceChangePct?.toDouble(),
            isFlagged = s.isFlagged,
            flaggedReason = s.flaggedReason,
            wasteScore = s.wasteScore?.toDouble(),
            wasteReasons = s.wasteReasons.map { it.reason },
            transactionIds = s.transactionIds.map { it.toString() },
            createdAt = s.createdAt.toString()
        )
    }
}

@Serializable
data class SubscriptionSummaryResponse(
    val totalMonthlyCost: String,
    val currency: String = "INR",
    val activeCount: Long,
    val flaggedCount: Long,
    val cancelledCount: Long,
    val byCategory: Map<String, CategoryBreakdown>
)

@Serializable
data class CategoryBreakdown(
    val count: Int,
    val monthly: String
)

@Serializable
data class BillResponse(
    val id: String,
    val billType: String,
    val billerName: String,
    val amount: String,
    val minimumDue: String?,
    val currency: String,
    val dueDate: String,
    val status: String,
    val paidAt: String?,
    val paidAmount: String?,
    val paymentTxnId: String?,
    val createdAt: String
) {
    companion object {
        fun from(b: Bill) = BillResponse(
            id = b.id.toString(),
            billType = b.billType.name.lowercase(),
            billerName = b.billerName,
            amount = b.amount.toMoney(),
            minimumDue = b.minimumDue?.toMoney(),
            currency = b.currency,
            dueDate = b.dueDate.toString(),
            status = b.status.name.lowercase(),
            paidAt = b.paidAt?.toString(),
            paidAmount = b.paidAmount?.toMoney(),
            paymentTxnId = b.paymentTxnId?.toString(),
            createdAt = b.createdAt.toString()
        )
    }
}
