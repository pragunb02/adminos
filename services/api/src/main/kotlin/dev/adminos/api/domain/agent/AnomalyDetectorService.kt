package dev.adminos.api.domain.agent

import dev.adminos.api.domain.common.workflow.AnomalyRule
import dev.adminos.api.domain.common.workflow.RuleResult
import dev.adminos.api.domain.common.workflow.TransactionData
import dev.adminos.api.domain.financial.TransactionRepository
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.UUID

/**
 * Anomaly detection service with 5 deterministic rules.
 * Evaluates transactions and creates anomaly records when rules fire.
 */
class AnomalyDetectorService(
    private val anomalyRepository: AnomalyRepository,
    private val transactionRepository: TransactionRepository
) {
    private val logger = LoggerFactory.getLogger(AnomalyDetectorService::class.java)

    /**
     * Evaluate a transaction against all anomaly rules.
     * Fetches contextual data from the repository to construct rules with context.
     * Returns the created anomaly if any rules fire, null otherwise.
     */
    suspend fun evaluate(txn: TransactionData): Anomaly? {
        val userId = UUID.fromString(txn.userId)
        val categoryAvg = transactionRepository.getCategoryAverage(userId, txn.category, 30)
        val recentTxns = transactionRepository.getRecentByMerchant(userId, txn.merchantName, 24)

        val recentTransactionData = recentTxns.map { t ->
            TransactionData(
                userId = t.userId.toString(),
                amount = t.amount,
                currency = t.currency,
                merchantName = t.merchantName ?: "",
                merchantRaw = t.merchantRaw,
                accountLast4 = t.accountLast4,
                paymentMethod = t.paymentMethod?.name,
                category = t.category.name.lowercase(),
                transactedAt = t.transactedAt,
                transactionId = t.id.toString()
            )
        }

        val contextualRules: List<AnomalyRule> = listOf(
            LargeAmountRule(mapOf(txn.category to categoryAvg)),
            ForeignCurrencyRule(),
            OddHoursRule(),
            CardTestingRule(),
            DuplicateChargeRule(recentTransactionData)
        )

        val fired = contextualRules.mapNotNull { it.evaluate(txn) }
        if (fired.isEmpty()) return null

        val confidence = computeConfidence(fired)
        val primaryType = determineType(fired)
        val reason = fired.joinToString("; ") { it.reason }

        val transactionId = if (txn.transactionId != null) {
            UUID.fromString(txn.transactionId)
        } else {
            UUID.randomUUID()
        }

        val anomaly = Anomaly(
            userId = userId,
            transactionId = transactionId,
            type = primaryType,
            confidenceScore = confidence,
            reason = reason
        )

        val saved = anomalyRepository.save(anomaly)
        logger.info("Anomaly detected: user={}, type={}, confidence={}, rules={}",
            txn.userId, primaryType, confidence, fired.map { it.ruleName })
        return saved
    }

    companion object {
        /**
         * Multi-rule confidence scoring:
         * confidence = max(individual) + 0.05 * (count - 1), capped at 1.0
         */
        fun computeConfidence(fired: List<RuleResult>): Double {
            if (fired.isEmpty()) return 0.0
            val maxConf = fired.maxOf { it.confidence }
            val bonus = 0.05 * (fired.size - 1)
            return (maxConf + bonus).coerceAtMost(1.0)
        }

        fun determineType(fired: List<RuleResult>): AnomalyType {
            val highest = fired.maxByOrNull { it.confidence } ?: return AnomalyType.LARGE_AMOUNT
            return when {
                highest.ruleName.contains("card_testing") -> AnomalyType.CARD_TESTING
                highest.ruleName.contains("foreign") -> AnomalyType.FOREIGN_CURRENCY
                highest.ruleName.contains("odd_hours") -> AnomalyType.ODD_HOURS
                highest.ruleName.contains("duplicate") -> AnomalyType.DUPLICATE_CHARGE
                else -> AnomalyType.LARGE_AMOUNT
            }
        }
    }
}
