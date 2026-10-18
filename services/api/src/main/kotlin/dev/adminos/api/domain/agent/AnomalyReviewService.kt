package dev.adminos.api.domain.agent

import dev.adminos.api.domain.audit.ActorType
import dev.adminos.api.domain.audit.AuditActions
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.financial.MerchantKey
import dev.adminos.api.domain.financial.TransactionRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID

/** What resolving an anomaly did. */
sealed interface AnomalyResolution {
    /** Resolved; [tolerances] are the per-merchant tolerances the answer moved (empty when it taught nothing). */
    data class Resolved(val anomaly: Anomaly, val tolerances: List<AnomalyTolerance>) : AnomalyResolution
    /** No such anomaly for this user (someone else's looks the same, so nothing leaks). */
    data object NotFound : AnomalyResolution
}

/**
 * The user's answer to an unusual-charge alert (AOS-713, D-73). Besides recording the status, the
 * **first** answer teaches the amount rule about that merchant:
 * - "this is normal" (`confirmed_safe` or `dismissed`) raises the tolerance one step, capped;
 * - "this was unexpected" (`confirmed_fraud`) lowers it one step, never below the start.
 * Changing a resolved answer later is recorded (status and audit) but teaches nothing more, so the
 * same alert can't be counted twice. Crisp rules (duplicate, card testing…) are never relaxed.
 */
class AnomalyReviewService(
    private val anomalies: AnomalyRepository,
    private val transactions: TransactionRepository,
    private val tolerances: AnomalyToleranceRepository,
    private val audit: AuditService,
    private val clock: Clock = Clock.systemUTC(),
    private val unitOfWork: suspend (suspend () -> Unit) -> Unit = { it() }
) {
    private val logger = LoggerFactory.getLogger(AnomalyReviewService::class.java)

    /** One page of the user's anomalies, newest first, and the total for the filter. */
    suspend fun list(userId: UUID, status: AnomalyStatus?, cursor: String?, limit: Int): Pair<List<Anomaly>, Long> =
        anomalies.findByUserId(userId, status, cursor, limit) to anomalies.countByUserId(userId, status)

    /** The user's anomaly, or null when there is none with this id for them. */
    suspend fun find(userId: UUID, anomalyId: UUID): Anomaly? = anomalies.findById(anomalyId)?.takeIf { it.userId == userId }

    suspend fun resolve(userId: UUID, anomalyId: UUID, status: AnomalyStatus): AnomalyResolution {
        require(status != AnomalyStatus.OPEN) { "An anomaly is resolved to a decided status, not open" }
        val anomaly = find(userId, anomalyId) ?: return AnomalyResolution.NotFound
        val now = clock.instant()
        val feedback = if (anomaly.status == AnomalyStatus.OPEN) feedbackFor(status) else null
        var updated = anomaly
        var moved = emptyList<AnomalyTolerance>()
        unitOfWork {
            updated = anomalies.update(anomaly.copy(status = status, resolvedAt = now, resolvedBy = AnomalyResolver.USER))
            moved = feedback?.let { teach(anomaly, it) }.orEmpty()
        }
        audit.log(
            userId = userId, actor = ActorType.USER, action = auditAction(status), entityType = "anomaly", entityId = anomalyId,
            beforeState = JsonObject(mapOf("status" to JsonPrimitive(anomaly.status.name.lowercase()))),
            afterState = JsonObject(mapOf("status" to JsonPrimitive(status.name.lowercase())))
        )
        return AnomalyResolution.Resolved(updated, moved)
    }

    private suspend fun teach(anomaly: Anomaly, feedback: AlertFeedback): List<AnomalyTolerance> {
        val rules = tolerableRules(anomaly)
        if (rules.isEmpty()) return emptyList()
        val merchant = anomaly.merchantKey ?: MerchantKey.of(transactions.findById(anomaly.transactionId)?.merchantName)
        if (merchant == null) {
            logger.info("Anomaly {} has no merchant: its answer can't move a tolerance", anomaly.id)
            return emptyList()
        }
        return rules.map { rule ->
            tolerances.record(anomaly.userId, merchant, rule, feedback, clock.instant()).also {
                logger.info("Tolerance for {} at anomaly {}'s merchant is now {} ({})", rule.code, anomaly.id, it.level.steps, feedback)
            }
        }
    }

    companion object {
        fun feedbackFor(status: AnomalyStatus): AlertFeedback? = when (status) {
            AnomalyStatus.CONFIRMED_SAFE, AnomalyStatus.DISMISSED -> AlertFeedback.DISMISSED
            AnomalyStatus.CONFIRMED_FRAUD -> AlertFeedback.CONFIRMED
            AnomalyStatus.OPEN -> null
        }

        /** The rules a user can relax among those that fired; rows from before V44 only know their type. */
        fun tolerableRules(anomaly: Anomaly): List<TolerableRule> {
            val fired = anomaly.rulesFired.ifEmpty { if (anomaly.type == AnomalyType.LARGE_AMOUNT) setOf(RobustAmountRule.NAME) else emptySet() }
            return fired.mapNotNull { TolerableRule.fromCode(it) }.distinct()
        }

        fun auditAction(status: AnomalyStatus): String = when (status) {
            AnomalyStatus.CONFIRMED_SAFE -> AuditActions.ANOMALY_CONFIRM_SAFE
            AnomalyStatus.CONFIRMED_FRAUD -> AuditActions.ANOMALY_CONFIRM_FRAUD
            AnomalyStatus.DISMISSED -> AuditActions.ANOMALY_DISMISS
            AnomalyStatus.OPEN -> "anomaly.resolve"
        }
    }
}
