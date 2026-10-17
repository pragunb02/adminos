package dev.adminos.api.domain.ai

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** How a provider call ended, for the audit log. */
enum class AiCallOutcome {
    /** A validated answer was returned to the feature. */
    ACCEPTED,

    /** The model answered, but the answer was refused, cut off or failed validation. */
    REJECTED,

    /** No answer: the provider failed (network, HTTP status, bad reply). */
    ERROR
}

/**
 * One row per provider call (D-67, DPDP evidence for AOS-602). It holds **no content**: no prompt, no model
 * output, no message text, no merchant name. Strings are limited to ids and codes ([AiReasonCode]).
 */
data class AiCallRecord(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val feature: AiFeature,
    val provider: AiProviderKind,
    val model: String,
    val promptVersion: String,
    val usage: AiUsage,
    val costMicros: Long,
    val outcome: AiCallOutcome,
    /** Why it was rejected or failed; null when accepted. */
    val code: AiReasonCode?,
    val latencyMs: Long,
    val at: Instant
) {
    init {
        require(model.matches(ID) && promptVersion.matches(ID)) { "Model and prompt version are ids" }
        require(costMicros >= 0 && latencyMs >= 0) { "Cost and latency are not negative" }
        require((outcome == AiCallOutcome.ACCEPTED) == (code == null)) { "Only an accepted call has no reason code" }
    }

    companion object {
        /** Model ids (`claude-haiku-5-5`, `qwen3:4b`) and prompt versions (`categorize-merchant-v1`). */
        val ID = Regex("[a-z0-9][a-z0-9._:/\\-]{0,99}")
    }
}

interface AiCallLogRepository {
    suspend fun record(call: AiCallRecord)

    /** What [userId]'s calls for [feature] cost in [month] (UTC). */
    suspend fun spentMicros(userId: UUID, feature: AiFeature, month: YearMonth): Long
}

class InMemoryAiCallLogRepository : AiCallLogRepository {
    private val calls = CopyOnWriteArrayList<AiCallRecord>()

    /** Everything recorded, oldest first (tests). */
    val all: List<AiCallRecord> get() = calls.toList()

    override suspend fun record(call: AiCallRecord) {
        calls += call
    }

    override suspend fun spentMicros(userId: UUID, feature: AiFeature, month: YearMonth): Long =
        calls.filter { it.userId == userId && it.feature == feature && YearMonth.from(it.at.atOffset(ZoneOffset.UTC)) == month }.sumOf { it.costMicros }
}
