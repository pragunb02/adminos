package dev.adminos.api.domain.common.workflow

import kotlinx.serialization.json.JsonObject

/**
 * Base abstraction for all workflows in AdminOS.
 * Template Method pattern: parse → execute → persist → notify
 *
 * Every use case (anomaly detection, waste scoring, briefing generation, etc.)
 * implements this interface. The JobRouter dispatches to the correct handler.
 *
 * Used by: Task 7 (Financial Intelligence), Task 9 (Agent Layer)
 */
abstract class JobHandler<TPayload, TResult>(
    val jobType: JobType,
    val triggerType: TriggerType
) {
    abstract suspend fun parse(payload: JsonObject): TPayload
    abstract suspend fun execute(payload: TPayload): TResult
    abstract suspend fun persist(result: TResult)
    open suspend fun notify(result: TResult) {}
    open suspend fun onFailure(error: Throwable, payload: TPayload) {}

    /** Template method — the worker/scheduler calls this */
    suspend fun run(rawPayload: JsonObject) {
        val payload = parse(rawPayload)
        try {
            val result = execute(payload)
            persist(result)
            notify(result)
        } catch (e: Throwable) {
            onFailure(e, payload)
            throw e
        }
    }
}

enum class TriggerType {
    CRON, EVENT, USER_ACTION
}

enum class JobType(val value: String) {
    GMAIL_INGEST("gmail_ingest"),
    SMS_PROCESS("sms_process"),
    PDF_PARSE("pdf_parse"),
    AGENT_BRIEFING("agent_briefing"),
    SUBSCRIPTION_DETECT("subscription_detect"),
    ANOMALY_CHECK("anomaly_check"),
    WASTE_SCORE("waste_score"),
    CATEGORIZE_FALLBACK("categorize_fallback"),
    CANCELLATION_DRAFT("cancellation_draft");

    companion object {
        fun fromValue(value: String): JobType =
            entries.find { it.value == value }
                ?: throw IllegalArgumentException("Unknown job type: $value")
    }
}
