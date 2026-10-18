package dev.adminos.api.domain.agent

import dev.adminos.api.domain.agent.baselines.ToleranceLevel
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Rules whose threshold a user can relax per merchant (AOS-713, D-73). Crisp rules (duplicates,
 * card testing, foreign currency, odd hours) are deliberately not here: a dismissal never hides them.
 */
enum class TolerableRule(val code: String) {
    LARGE_AMOUNT(RobustAmountRule.NAME);

    companion object {
        fun fromCode(code: String): TolerableRule? = entries.firstOrNull { it.code == code }
    }
}

/** What the user said about an alert, and how it moves the tolerance. */
enum class AlertFeedback(val step: Int) {
    /** "This is normal": alert less here. */
    DISMISSED(+1),
    /** "This was unexpected": the alert was right, so don't stay relaxed. */
    CONFIRMED(-1)
}

/** One (user, merchant, rule) tolerance, with how it got there. */
data class AnomalyTolerance(
    val userId: UUID,
    val merchantKey: String,
    val rule: TolerableRule,
    val level: ToleranceLevel = ToleranceLevel.DEFAULT,
    val dismissals: Int = 0,
    val confirmations: Int = 0,
    val updatedAt: Instant = Instant.now()
) {
    init {
        require(merchantKey.isNotBlank()) { "A tolerance belongs to a merchant" }
        require(dismissals >= 0 && confirmations >= 0) { "Feedback counts are never negative" }
    }

    /** This tolerance after [feedback] at [at]: the level moves one step (clamped) and the count goes up. */
    fun after(feedback: AlertFeedback, at: Instant): AnomalyTolerance = copy(
        level = level.moved(feedback.step),
        dismissals = dismissals + if (feedback == AlertFeedback.DISMISSED) 1 else 0,
        confirmations = confirmations + if (feedback == AlertFeedback.CONFIRMED) 1 else 0,
        updatedAt = at
    )
}

interface AnomalyToleranceRepository {
    suspend fun find(userId: UUID, merchantKey: String, rule: TolerableRule): AnomalyTolerance?

    /** Applies [feedback] atomically (creating the row at the default level first). @return the new tolerance */
    suspend fun record(userId: UUID, merchantKey: String, rule: TolerableRule, feedback: AlertFeedback, at: Instant): AnomalyTolerance
}

class InMemoryAnomalyToleranceRepository : AnomalyToleranceRepository {
    private val store = ConcurrentHashMap<Triple<UUID, String, TolerableRule>, AnomalyTolerance>()

    override suspend fun find(userId: UUID, merchantKey: String, rule: TolerableRule): AnomalyTolerance? =
        store[Triple(userId, merchantKey, rule)]

    override suspend fun record(userId: UUID, merchantKey: String, rule: TolerableRule, feedback: AlertFeedback, at: Instant): AnomalyTolerance =
        synchronized(this) {
            val key = Triple(userId, merchantKey, rule)
            val next = (store[key] ?: AnomalyTolerance(userId, merchantKey, rule, updatedAt = at)).after(feedback, at)
            store[key] = next
            next
        }
}
