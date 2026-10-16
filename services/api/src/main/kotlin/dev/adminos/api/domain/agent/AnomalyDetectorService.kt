package dev.adminos.api.domain.agent

import dev.adminos.api.domain.common.DuplicateEntityException
import dev.adminos.api.domain.common.workflow.AnomalyRule
import dev.adminos.api.domain.common.workflow.RuleResult
import dev.adminos.api.domain.common.workflow.TransactionData
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionRepository
import dev.adminos.api.domain.identity.UserRepository
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.Duration
import java.time.ZoneId

/**
 * Checks a new spend against the five deterministic rules (AOS-505) and records an anomaly when
 * any fire. Context is taken **relative to the transaction's own time**, so an imported statement
 * from March is compared with February, not with today:
 * - category average: the user's spends in the same category in the 30 days before it (at least
 *   [MIN_HISTORY] of them, so a first-ever purchase isn't "3x the average");
 * - duplicates: the same amount at the same merchant in the 24 hours up to it;
 * - odd hours: in the user's own time zone.
 * One anomaly per transaction at most, so a re-processed event never creates a second.
 */
class AnomalyDetectorService(
    private val anomalyRepository: AnomalyRepository,
    private val transactionRepository: TransactionRepository,
    private val users: UserRepository
) {
    private val logger = LoggerFactory.getLogger(AnomalyDetectorService::class.java)

    /** @return the new anomaly, or null if nothing fired, it isn't a spend, or it was already checked */
    suspend fun evaluate(transaction: Transaction): Anomaly? {
        if (transaction.nature != TransactionNature.SPEND) return null
        if (anomalyRepository.findByTransactionId(transaction.id) != null) return null

        val subject = transaction.toRuleData()
        val history = transactionRepository.findByUserId(
            userId = transaction.userId,
            from = transaction.transactedAt.minus(AVERAGE_WINDOW),
            to = transaction.transactedAt,
            limit = HISTORY_LIMIT,
            nature = TransactionNature.SPEND
        ).filter { it.id != transaction.id }

        val rules: List<AnomalyRule> = listOf(
            LargeAmountRule(categoryAverage(transaction, history)?.let { mapOf(subject.category to it) } ?: emptyMap(), LARGE_MINIMUM),
            ForeignCurrencyRule(),
            OddHoursRule(zoneOf(transaction)),
            CardTestingRule(),
            DuplicateChargeRule(history.filter { !it.transactedAt.isBefore(transaction.transactedAt.minus(DUPLICATE_WINDOW)) }.map { it.toRuleData() })
        )
        val fired = rules.mapNotNull { it.evaluate(subject) }
        if (fired.isEmpty()) return null

        val anomaly = Anomaly(
            userId = transaction.userId,
            transactionId = transaction.id,
            type = determineType(fired),
            confidenceScore = computeConfidence(fired),
            reason = fired.joinToString("; ") { it.reason }
        )
        return try {
            anomalyRepository.save(anomaly).also {
                logger.info("Anomaly {} for transaction {}: rules={}, confidence={}", it.id, transaction.id, fired.map { r -> r.ruleName }, it.confidenceScore)
            }
        } catch (e: DuplicateEntityException) {
            null // checked concurrently by another instance: one anomaly per transaction
        }
    }

    /** Mean of earlier same-category spends in the window, or null with too little history. */
    private fun categoryAverage(t: Transaction, history: List<Transaction>): BigDecimal? {
        val earlier = history.filter { it.category == t.category && it.transactedAt.isBefore(t.transactedAt) }
        if (earlier.size < MIN_HISTORY) return null
        return earlier.fold(BigDecimal.ZERO) { sum, x -> sum + x.amount }.divide(BigDecimal(earlier.size), 2, RoundingMode.HALF_UP)
    }

    private suspend fun zoneOf(t: Transaction): ZoneId {
        val id = users.findById(t.userId)?.timezone ?: return DEFAULT_ZONE
        return try { ZoneId.of(id) } catch (e: DateTimeException) { DEFAULT_ZONE }
    }

    private fun Transaction.toRuleData() = TransactionData(
        userId = userId.toString(),
        amount = amount,
        currency = currency,
        merchantName = merchantName.orEmpty(),
        merchantRaw = merchantRaw,
        accountLast4 = accountLast4,
        paymentMethod = paymentMethod?.name,
        category = category.name.lowercase(),
        transactedAt = transactedAt,
        transactionId = id.toString()
    )

    companion object {
        val DEFAULT_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
        val AVERAGE_WINDOW: Duration = Duration.ofDays(30)
        val DUPLICATE_WINDOW: Duration = Duration.ofHours(24)
        /** Fewer earlier spends than this in a category means no average to compare with. */
        const val MIN_HISTORY = 3
        /** Large-amount alerts only from ₹2,000. */
        val LARGE_MINIMUM = BigDecimal(2000)
        private const val HISTORY_LIMIT = 5000

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
