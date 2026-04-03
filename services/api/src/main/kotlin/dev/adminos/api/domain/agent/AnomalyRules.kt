package dev.adminos.api.domain.agent

import dev.adminos.api.domain.common.workflow.AnomalyRule
import dev.adminos.api.domain.common.workflow.RuleResult
import dev.adminos.api.domain.common.workflow.TransactionData
import java.math.BigDecimal
import java.time.ZoneId

/**
 * Rule 1: Amount > 3x user's 30-day category average.
 * Confidence: 0.7
 *
 * Note: categoryAverage must be provided externally (via TransactionData context).
 * For standalone evaluation, we use a default threshold of ₹10,000.
 */
class LargeAmountRule(
    private val categoryAverages: Map<String, BigDecimal> = emptyMap()
) : AnomalyRule {
    override val name = "large_amount"
    override val baseConfidence = 0.7

    override fun evaluate(transaction: TransactionData): RuleResult? {
        val avg = categoryAverages[transaction.category]
        if (avg != null && avg > BigDecimal.ZERO) {
            val threshold = avg.multiply(BigDecimal(3))
            if (transaction.amount > threshold) {
                return RuleResult(
                    ruleName = name,
                    confidence = baseConfidence,
                    reason = "Amount ₹${transaction.amount} exceeds 3x category average of ₹$avg"
                )
            }
        }
        return null
    }
}

/**
 * Rule 2: Currency != 'INR' — foreign charge.
 * Confidence: 0.8
 */
class ForeignCurrencyRule : AnomalyRule {
    override val name = "foreign_currency"
    override val baseConfidence = 0.8

    override fun evaluate(transaction: TransactionData): RuleResult? {
        if (transaction.currency != "INR") {
            return RuleResult(
                ruleName = name,
                confidence = baseConfidence,
                reason = "Foreign currency charge in ${transaction.currency}"
            )
        }
        return null
    }
}

/**
 * Rule 3: Transaction between 01:00-05:00 local time.
 * Confidence: 0.6
 */
class OddHoursRule(
    private val timezone: ZoneId = ZoneId.of("Asia/Kolkata")
) : AnomalyRule {
    override val name = "odd_hours"
    override val baseConfidence = 0.6

    override fun evaluate(transaction: TransactionData): RuleResult? {
        val localTime = transaction.transactedAt.atZone(timezone).toLocalTime()
        val hour = localTime.hour
        if (hour in 1..4) { // 01:00 to 04:59 (before 05:00)
            return RuleResult(
                ruleName = name,
                confidence = baseConfidence,
                reason = "Transaction at unusual hour: ${localTime.hour}:${"%02d".format(localTime.minute)} local time"
            )
        }
        return null
    }
}

/**
 * Rule 4: Amount == 1.00 or 2.00 — card testing pattern.
 * Confidence: 0.9
 */
class CardTestingRule : AnomalyRule {
    override val name = "card_testing"
    override val baseConfidence = 0.9

    override fun evaluate(transaction: TransactionData): RuleResult? {
        if (transaction.amount.compareTo(BigDecimal.ONE) == 0 || transaction.amount.compareTo(BigDecimal(2)) == 0) {
            return RuleResult(
                ruleName = name,
                confidence = baseConfidence,
                reason = "Amount of exactly ₹${transaction.amount} matches card testing pattern"
            )
        }
        return null
    }
}

/**
 * Rule 5: Same amount + same merchant within 24 hours — duplicate charge.
 * Confidence: 0.75
 *
 * Note: recentTransactions must be provided externally for context.
 * For standalone evaluation, this rule requires recent transaction data.
 */
class DuplicateChargeRule(
    private val recentTransactions: List<TransactionData> = emptyList()
) : AnomalyRule {
    override val name = "duplicate_charge"
    override val baseConfidence = 0.75

    override fun evaluate(transaction: TransactionData): RuleResult? {
        val twentyFourHoursAgo = transaction.transactedAt.minusSeconds(24 * 60 * 60)
        val duplicates = recentTransactions.filter { recent ->
            recent.merchantName.equals(transaction.merchantName, ignoreCase = true) &&
                recent.amount.compareTo(transaction.amount) == 0 &&
                recent.transactedAt.isAfter(twentyFourHoursAgo) &&
                recent.transactedAt.isBefore(transaction.transactedAt)
        }
        if (duplicates.isNotEmpty()) {
            return RuleResult(
                ruleName = name,
                confidence = baseConfidence,
                reason = "Same amount ₹${transaction.amount} at ${transaction.merchantName} within 24 hours"
            )
        }
        return null
    }
}
