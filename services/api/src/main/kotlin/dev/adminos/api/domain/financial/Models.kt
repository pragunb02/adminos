package dev.adminos.api.domain.financial

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ── Enums ──

enum class TransactionType { DEBIT, CREDIT, TRANSFER, REFUND, REVERSAL }
enum class PaymentMethod { UPI, CARD_DEBIT, CARD_CREDIT, NETBANKING, CASH, WALLET }
enum class TransactionCategory {
    FOOD, TRANSPORT, SHOPPING, UTILITIES, EMI, SUBSCRIPTION,
    ENTERTAINMENT, HEALTH, EDUCATION, TRANSFER, OTHER
}
enum class TransactionStatus { COMPLETED, PENDING, FAILED, REVERSED }
enum class AccountType { SAVINGS, CURRENT, CREDIT_CARD, WALLET, UPI }
enum class BillingCycle { DAILY, WEEKLY, MONTHLY, QUARTERLY, YEARLY }
enum class SubscriptionStatus { ACTIVE, CANCELLED, PAUSED, TRIAL, UNKNOWN }
enum class SubscriptionCategory {
    ENTERTAINMENT, FITNESS, PRODUCTIVITY, CLOUD, FOOD, NEWS, GAMING, OTHER
}
enum class UsageStatus { ACTIVE, UNUSED, UNKNOWN }
enum class BillType {
    CREDIT_CARD, ELECTRICITY, INTERNET, MOBILE, RENT, INSURANCE, EMI, WATER, GAS, OTHER
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
    val merchantName: String? = null,
    val merchantRaw: String? = null,
    val merchantCategory: String? = null,
    val accountId: UUID? = null,
    val accountLast4: String? = null,
    val paymentMethod: PaymentMethod? = null,
    val upiVpa: String? = null,
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
    val transactedAt: Instant,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

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
    val metadata: Map<String, Any?>? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

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
