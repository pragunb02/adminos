package dev.adminos.api.domain.common.workflow

import java.math.BigDecimal
import java.time.Instant

/** Strategy: evaluates a transaction for anomalies (Task 9.1) */
interface AnomalyRule {
    val name: String
    val baseConfidence: Double
    fun evaluate(transaction: TransactionData): RuleResult?
}

data class RuleResult(
    val ruleName: String,
    val confidence: Double,
    val reason: String
)

/** Strategy: categorizes a transaction by merchant name (Task 7.1) */
interface CategorizationStrategy {
    val priority: Int
    fun categorize(merchantName: String, amount: BigDecimal, paymentMethod: String?): CategoryResult?
}

data class CategoryResult(
    val category: String,
    val subcategory: String? = null,
    val confidence: Double,
    val source: String // "rules", "ai", "user"
)

/** Strategy: evaluates a subscription for waste signals (Task 9.6) */
interface WasteSignal {
    val name: String
    val weight: Double
    fun evaluate(subscription: SubscriptionData, gmailSignals: GmailSignals): Double
}

// --- Shared data types ---

data class TransactionData(
    val userId: String,
    val amount: BigDecimal,
    val currency: String = "INR",
    val merchantName: String,
    val merchantRaw: String? = null,
    val accountLast4: String? = null,
    val paymentMethod: String? = null,
    val category: String = "other",
    val transactedAt: Instant,
    val transactionId: String? = null
)

data class SubscriptionData(
    val id: String,
    val userId: String,
    val name: String,
    val merchantName: String,
    val amount: BigDecimal,
    val billingCycle: String,
    val firstBilledDate: Instant,
    val lastBilledDate: Instant,
    val status: String
)

data class GmailSignals(
    val loginEmailCount: Int = 0,
    val usageEmailCount: Int = 0,
    val lastLoginEmail: Instant? = null,
    val lastUsageEmail: Instant? = null
)
// Note: ConfidenceScores is defined in the Go workers (workflow/strategies.go)
// and stored in transaction metadata JSONB. No Kotlin equivalent needed —
// the API reads it as raw JsonObject from the metadata field.
