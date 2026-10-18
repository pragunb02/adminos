package dev.adminos.api.domain.agent

import dev.adminos.api.domain.agent.baselines.ToleranceLevel
import dev.adminos.api.domain.common.DuplicateEntityException
import dev.adminos.api.domain.common.workflow.AnomalyRule
import dev.adminos.api.domain.common.workflow.RuleResult
import dev.adminos.api.domain.common.workflow.TransactionData
import dev.adminos.api.domain.financial.MerchantKey
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionRepository
import dev.adminos.api.domain.identity.UserCalendar
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Checks a new spend and records an anomaly when any rule fires (AOS-505, AOS-713). Context is taken
 * **relative to the transaction's own time**, so an imported statement from March is compared with
 * the months before March, not with today:
 * - amount: [RobustAmountRule], per-merchant then per-category robust baselines in the user's days,
 *   relaxed by what the user said about earlier alerts at that merchant ([AnomalyToleranceRepository]);
 * - duplicates: the same amount at the same merchant in the 24 hours up to it;
 * - odd hours: in the user's own time zone;
 * - foreign currency and card testing: from the spend alone.
 * One anomaly per transaction at most, so a re-processed event never creates a second.
 */
class AnomalyDetectorService(
    private val anomalyRepository: AnomalyRepository,
    private val transactionRepository: TransactionRepository,
    private val calendar: UserCalendar,
    private val tolerances: AnomalyToleranceRepository,
    private val amountRule: RobustAmountRule = RobustAmountRule(),
    /** Duplicates below this are recorded, not pushed ([DuplicateChargeRule]); zero is the AOS-505 behaviour (for the eval). */
    private val smallDuplicate: java.math.BigDecimal = DuplicateChargeRule.SMALL_AMOUNT
) {
    private val logger = LoggerFactory.getLogger(AnomalyDetectorService::class.java)

    /** @return the new anomaly, or null if nothing fired, it isn't a spend, or it was already checked */
    suspend fun evaluate(transaction: Transaction): Anomaly? {
        if (transaction.nature != TransactionNature.SPEND) return null
        if (anomalyRepository.findByTransactionId(transaction.id) != null) return null

        val zone = calendar.zoneOf(transaction.userId)
        val history = transactionRepository.findByUserId(
            userId = transaction.userId,
            from = RobustAmountRule.windowStart(transaction.transactedAt, amountRule.lookbackDays, zone),
            to = transaction.transactedAt,
            limit = HISTORY_LIMIT,
            nature = TransactionNature.SPEND
        ).filter { it.id != transaction.id }
        val merchantKey = MerchantKey.of(transaction.merchantName)

        val subject = transaction.toRuleData()
        val crisp: List<AnomalyRule> = listOf(
            ForeignCurrencyRule(),
            OddHoursRule(zone),
            CardTestingRule(),
            DuplicateChargeRule(history.filter { !it.transactedAt.isBefore(transaction.transactedAt.minus(DUPLICATE_WINDOW)) }.map { it.toRuleData() }, smallDuplicate)
        )
        val amount = amountRule.evaluate(transaction, AmountContext(history, zone, toleranceAt(transaction, merchantKey)))
        val fired = listOfNotNull(amount) + crisp.mapNotNull { it.evaluate(subject) }
        if (fired.isEmpty()) return null

        val anomaly = Anomaly(
            userId = transaction.userId,
            transactionId = transaction.id,
            type = determineType(fired),
            confidenceScore = computeConfidence(fired),
            reason = fired.joinToString("; ") { it.reason },
            rulesFired = fired.map { it.ruleName }.toSet(),
            merchantKey = merchantKey
        )
        return try {
            anomalyRepository.save(anomaly).also {
                logger.info("Anomaly {} for transaction {}: rules={}, confidence={}", it.id, transaction.id, fired.map { r -> r.ruleName }, it.confidenceScore)
            }
        } catch (e: DuplicateEntityException) {
            null // checked concurrently by another instance: one anomaly per transaction
        }
    }

    private suspend fun toleranceAt(t: Transaction, merchantKey: String?): ToleranceLevel =
        merchantKey?.let { tolerances.find(t.userId, it, TolerableRule.LARGE_AMOUNT)?.level } ?: ToleranceLevel.DEFAULT

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
        val DUPLICATE_WINDOW: Duration = Duration.ofHours(24)
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
