package dev.adminos.api.domain.financial

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ── Enums ──

enum class TransactionType { DEBIT, CREDIT, TRANSFER, REFUND, REVERSAL }
/** How money left; PAY_LATER is a BNPL purchase, repaid later from the bank (AOS-909, V39). */
enum class PaymentMethod { UPI, CARD_DEBIT, CARD_CREDIT, NETBANKING, CASH, WALLET, PAY_LATER }
enum class TransactionCategory {
    FOOD, TRANSPORT, SHOPPING, UTILITIES, EMI, SUBSCRIPTION,
    ENTERTAINMENT, HEALTH, EDUCATION, TRANSFER, OTHER
}
/**
 * What a transaction is for the totals (D-39), independent of debit/credit. A [SETTLEMENT] is money paid to or
 * received from a friend to settle a shared bill (AOS-911, D-111): like a transfer, it is neither spending nor income.
 * `INVESTMENT` (AOS-815, D-79) is a kind of transfer too: money to or from the owner's investments, neither spent nor received.
 */
enum class TransactionNature { SPEND, INCOME, TRANSFER, REFUND, REVERSAL, SETTLEMENT, INVESTMENT;
    companion object {
        fun defaultFor(type: TransactionType) = when (type) {
            TransactionType.CREDIT -> INCOME
            TransactionType.TRANSFER -> TRANSFER
            TransactionType.REFUND -> REFUND
            TransactionType.REVERSAL -> REVERSAL
            TransactionType.DEBIT -> SPEND
        }
    }
}

enum class TransactionStatus { COMPLETED, PENDING, FAILED, REVERSED }
enum class AccountType { SAVINGS, CURRENT, CREDIT_CARD, WALLET, UPI }
enum class BillingCycle { DAILY, WEEKLY, MONTHLY, QUARTERLY, YEARLY;
    /** What [amount] charged once per this cycle costs per month (the summary's and the waste score's one rule). */
    fun monthlyCost(amount: BigDecimal): BigDecimal = when (this) {
        DAILY -> amount.multiply(BigDecimal(30))
        WEEKLY -> amount.multiply(BigDecimal("4.33"))
        MONTHLY -> amount
        QUARTERLY -> amount.divide(BigDecimal(3), 2, java.math.RoundingMode.HALF_UP)
        YEARLY -> amount.divide(BigDecimal(12), 2, java.math.RoundingMode.HALF_UP)
    }
}
enum class SubscriptionStatus { ACTIVE, CANCELLED, PAUSED, TRIAL, UNKNOWN }
enum class SubscriptionCategory {
    ENTERTAINMENT, FITNESS, PRODUCTIVITY, CLOUD, FOOD, NEWS, GAMING, OTHER
}
enum class UsageStatus { ACTIVE, UNUSED, UNKNOWN }
enum class BillType {
    CREDIT_CARD, ELECTRICITY, INTERNET, MOBILE, RENT, INSURANCE, EMI, WATER, GAS, OTHER,
    /** A pay-later provider's bill (AOS-909, V39). */
    PAY_LATER
}
enum class BillStatus { UPCOMING, DUE, OVERDUE, PAID, CANCELLED }

// ── Domain Models ──

data class Transaction(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val sourceType: String = "sms",
    val sourceRef: String? = null,
    val type: TransactionType,
    val amount: BigDecimal,
    val currency: String = "INR",
    val nature: TransactionNature = TransactionNature.defaultFor(type),
    val merchantName: String? = null,
    val merchantRaw: String? = null,
    val merchantCategory: String? = null,
    val accountId: UUID? = null,
    val accountLast4: String? = null,
    val paymentMethod: PaymentMethod? = null,
    val upiVpa: String? = null,
    /** UPI/IMPS reference (RRN), when known (AOS-207). */
    val paymentRef: String? = null,
    val category: TransactionCategory = TransactionCategory.OTHER,
    val subcategory: String? = null,
    val categorySource: String = "rules",
    val isRecurring: Boolean = false,
    val recurringGroupId: UUID? = null,
    val status: TransactionStatus = TransactionStatus.COMPLETED,
    val isAnomaly: Boolean = false,
    val anomalyId: UUID? = null,
    val isVerified: Boolean = true,
    val rawEmailId: String? = null,
    val metadata: Map<String, Any?>? = null,
    /** The intake event this transaction was created from (exactly once, V10). */
    val ingestEventId: UUID? = null,
    val transactedAt: Instant,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    /**
     * The owner's own part of a bill they paid for friends (AOS-911, D-111): what the totals count instead of
     * [amount]; the rest is owed by friends. Null when the transaction isn't split. Written only through
     * [TransactionRepository.setOwnShare], so a general update never clears it.
     */
    val ownShare: BigDecimal? = null
) {
    init {
        require(ownShare == null || (ownShare.signum() >= 0 && ownShare <= amount)) { "a share is between 0 and the amount" }
    }

    /** What this transaction counts as in the totals: the owner's share when split, else the amount. */
    val countedAmount: BigDecimal get() = ownShare ?: amount
}

data class Account(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val accountType: AccountType = AccountType.SAVINGS,
    val bankName: String? = null,
    val bankCode: String? = null,
    val accountLast4: String,
    val accountName: String? = null,
    val isPrimary: Boolean = false,
    val currency: String = "INR",
    val connectionId: UUID? = null,
    val metadata: Map<String, Any?>? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)


data class Subscription(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val name: String,
    val merchantName: String? = null,
    val category: SubscriptionCategory = SubscriptionCategory.OTHER,
    val amount: BigDecimal,
    val currency: String = "INR",
    val billingCycle: BillingCycle,
    val nextBillingDate: LocalDate? = null,
    val lastBilledDate: LocalDate? = null,
    val firstBilledDate: LocalDate? = null,
    val status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    val detectionSource: String = "sms",
    val usageStatus: UsageStatus = UsageStatus.UNKNOWN,
    val usageSignal: String? = null,
    val wasteScore: BigDecimal? = null,
    val wasteScoreUpdatedAt: Instant? = null,
    val priceChanged: Boolean = false,
    val priceChangePct: BigDecimal? = null,
    val isFlagged: Boolean = false,
    val flaggedReason: String? = null,
    val flaggedAt: Instant? = null,
    val flagDismissedAt: Instant? = null,
    val transactionIds: List<UUID> = emptyList(),
    val cancellationDraft: String? = null,
    val cancellationDraftGeneratedAt: Instant? = null,
    /** The renewal date the reminders in [renewalReminderDaysSent] were sent for (AOS-503). */
    val renewalRemindedFor: LocalDate? = null,
    val renewalReminderDaysSent: Set<Int> = emptySet(),
    /** Why [wasteScore] is what it is, strongest first (AOS-509, V24). */
    val wasteReasons: List<WasteReason> = emptyList(),
    /** The factors the user had seen when they last kept it: only a factor outside this set flags it again (AOS-509). */
    val wasteFactorsDismissed: Set<WasteFactor> = emptySet(),
    val metadata: Map<String, Any?>? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {
    init {
        require(wasteScore == null || (wasteScore.signum() >= 0 && wasteScore <= BigDecimal.ONE)) { "A waste score is between 0 and 1" }
    }

    /**
     * The user keeps it ("I use this"): the flag goes, and the factors behind the current score count
     * as seen, so tomorrow's score only flags it again for a new reason (AOS-509).
     */
    fun keep(at: Instant): Subscription = copy(
        usageStatus = UsageStatus.ACTIVE,
        isFlagged = false,
        flagDismissedAt = at,
        wasteFactorsDismissed = wasteReasons.filter { it.points > 0 }.map { it.factor }.toSet()
    )
}

data class Bill(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val billType: BillType = BillType.OTHER,
    val billerName: String,
    val accountRef: String? = null,
    val amount: BigDecimal,
    val minimumDue: BigDecimal? = null,
    val currency: String = "INR",
    val dueDate: LocalDate,
    val billingPeriodStart: LocalDate? = null,
    val billingPeriodEnd: LocalDate? = null,
    val status: BillStatus = BillStatus.UPCOMING,
    val paidAt: Instant? = null,
    val paidAmount: BigDecimal? = null,
    val paymentTxnId: UUID? = null,
    val detectionSource: String = "gmail",
    val sourceRef: String? = null,
    val reminderSent3d: Boolean = false,
    val reminderSent1d: Boolean = false,
    val sentReminderDays: Set<Int>? = null, // Tracks all custom reminder days sent (e.g., {7, 5, 3, 1})
    val metadata: Map<String, Any?>? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

// ── Categorization Result ──

data class CategorizationResult(
    val category: TransactionCategory,
    val subcategory: String? = null,
    val confidence: Double,
    val source: String = "rules"
)
