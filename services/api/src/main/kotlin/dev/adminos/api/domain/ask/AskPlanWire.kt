package dev.adminos.api.domain.ask

import dev.adminos.api.domain.ai.AiReading
import dev.adminos.api.domain.ai.AiReasonCode
import dev.adminos.api.domain.ai.SafeName
import dev.adminos.api.domain.analytics.Bucket
import dev.adminos.api.domain.financial.TransactionCategory
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * The JSON shape of an [AskPlan], read strictly (D-67: outputs are validated). The model's structured output,
 * the eval set's expected plans and "How I got this" all use it:
 * ```
 * {"action": "tools" | "decline", "reason": <decline reason> | null,
 *  "calls": [{"tool": <tool>, "period": {"preset": <preset>, "month": "2026-08" | null, "from": <date> | null, "to": <date> | null},
 *             "category": <category> | null, "merchant": <name> | null, "bucket": "day" | "week" | "month" | null, "limit": 1..10 | null}]}
 * ```
 * Every object has exactly these keys (structured outputs require them all), so a field the vocabulary doesn't
 * have (`userId`, `sql`, `url`) is rejected, never ignored. A tool's unused arguments must be null.
 */
object AskPlanWire {
    /** A plan is a few hundred bytes; anything much larger is not a plan. */
    const val MAX_TEXT = 8 * 1024

    val PLAN_FIELDS = setOf("action", "reason", "calls")
    val CALL_FIELDS = setOf("tool", "period", "category", "merchant", "bucket", "limit")
    val PERIOD_FIELDS = setOf("preset", "month", "from", "to")

    val TOO_LONG = AiReasonCode("too_long")
    val NOT_JSON_OBJECT = AiReasonCode("not_json_object")
    val UNEXPECTED_FIELDS = AiReasonCode("unexpected_fields")
    val BAD_ACTION = AiReasonCode("bad_action")
    val BAD_DECLINE_REASON = AiReasonCode("bad_decline_reason")
    val DECLINE_WITH_CALLS = AiReasonCode("decline_with_calls")
    val NO_CALLS = AiReasonCode("no_calls")
    val TOO_MANY_CALLS = AiReasonCode("too_many_calls")
    val DUPLICATE_CALLS = AiReasonCode("duplicate_calls")
    val BAD_CALL = AiReasonCode("bad_call")
    val UNKNOWN_TOOL = AiReasonCode("unknown_tool")
    val BAD_PERIOD = AiReasonCode("bad_period")
    val BAD_CATEGORY = AiReasonCode("bad_category")
    val BAD_MERCHANT = AiReasonCode("bad_merchant")
    val UNSAFE_MERCHANT = AiReasonCode("unsafe_merchant")
    val BAD_BUCKET = AiReasonCode("bad_bucket")
    val BAD_LIMIT = AiReasonCode("bad_limit")
    val ARGUMENT_NOT_ALLOWED = AiReasonCode("argument_not_allowed")

    private class Invalid(val code: AiReasonCode) : Exception(null, null, false, false)

    private fun invalid(code: AiReasonCode): Nothing = throw Invalid(code)

    fun read(text: String): AiReading<AskPlan> {
        if (text.length > MAX_TEXT) return AiReading.Invalid(TOO_LONG)
        val root = try {
            Json.parseToJsonElement(text.trim()) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return AiReading.Invalid(NOT_JSON_OBJECT)
        return try {
            AiReading.Valid(plan(root))
        } catch (e: Invalid) {
            AiReading.Invalid(e.code)
        }
    }

    private fun plan(root: JsonObject): AskPlan {
        if (root.keys != PLAN_FIELDS) invalid(UNEXPECTED_FIELDS)
        val calls = root["calls"] as? JsonArray ?: invalid(BAD_CALL)
        return when (string(root["action"], BAD_ACTION)) {
            "decline" -> {
                if (calls.isNotEmpty()) invalid(DECLINE_WITH_CALLS)
                AskPlan.Decline(DeclineReason.fromKey(string(root["reason"], BAD_DECLINE_REASON)) ?: invalid(BAD_DECLINE_REASON))
            }
            "tools" -> {
                if (root["reason"] !is JsonNull) invalid(BAD_DECLINE_REASON)
                if (calls.isEmpty()) invalid(NO_CALLS)
                if (calls.size > AskContract.MAX_CALLS) invalid(TOO_MANY_CALLS)
                val typed = calls.map { call(it as? JsonObject ?: invalid(BAD_CALL)) }
                if (typed.toSet().size != typed.size) invalid(DUPLICATE_CALLS)
                AskPlan.Tools(typed)
            }
            else -> invalid(BAD_ACTION)
        }
    }

    private fun call(obj: JsonObject): AskToolCall {
        if (obj.keys != CALL_FIELDS) invalid(UNEXPECTED_FIELDS)
        val tool = AskTool.fromKey(string(obj["tool"], UNKNOWN_TOOL)) ?: invalid(UNKNOWN_TOOL)
        val period = period(obj["period"] as? JsonObject ?: invalid(BAD_PERIOD))
        val category = nullableString(obj["category"], BAD_CATEGORY)?.let { c ->
            TransactionCategory.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == c } ?: invalid(BAD_CATEGORY)
        }
        val merchant = nullableString(obj["merchant"], BAD_MERCHANT)?.let { m ->
            if (!SafeName.isSafe(m)) invalid(UNSAFE_MERCHANT)
            MerchantQuery.of(m) ?: invalid(BAD_MERCHANT)
        }
        val bucket = nullableString(obj["bucket"], BAD_BUCKET)?.let { b -> Bucket.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == b } ?: invalid(BAD_BUCKET) }
        val limit = when (val l = obj["limit"]) {
            is JsonNull -> null
            is JsonPrimitive -> l.takeIf { !it.isString }?.intOrNull?.takeIf { it in 1..AskContract.MAX_LIMIT } ?: invalid(BAD_LIMIT)
            else -> invalid(BAD_LIMIT)
        }
        val given = buildSet {
            if (category != null) add("category")
            if (merchant != null) add("merchant")
            if (bucket != null) add("bucket")
            if (limit != null) add("limit")
        }
        fun only(vararg allowed: String) {
            if (!allowed.toSet().containsAll(given)) invalid(ARGUMENT_NOT_ALLOWED)
        }
        return when (tool) {
            AskTool.SPENDING_SUMMARY -> { only(); AskToolCall.SpendingSummary(period) }
            AskTool.CATEGORY_SPEND -> { only("category"); AskToolCall.CategorySpend(period, category) }
            AskTool.MERCHANT_SPEND -> { only("merchant"); AskToolCall.MerchantSpend(period, merchant ?: invalid(BAD_MERCHANT)) }
            AskTool.TOP_MERCHANTS -> { only("category", "limit"); AskToolCall.TopMerchants(period, category, limit ?: AskToolCall.TopMerchants.DEFAULT_LIMIT) }
            AskTool.SPENDING_TREND -> { only("bucket", "category"); AskToolCall.SpendingTrend(period, bucket ?: invalid(BAD_BUCKET), category) }
        }
    }

    private fun period(obj: JsonObject): PeriodSpec {
        if (obj.keys != PERIOD_FIELDS) invalid(UNEXPECTED_FIELDS)
        val preset = PeriodPreset.fromKey(string(obj["preset"], BAD_PERIOD)) ?: invalid(BAD_PERIOD)
        return try {
            PeriodSpec(
                preset,
                month = nullableString(obj["month"], BAD_PERIOD)?.let(YearMonth::parse),
                from = nullableString(obj["from"], BAD_PERIOD)?.let(LocalDate::parse),
                to = nullableString(obj["to"], BAD_PERIOD)?.let(LocalDate::parse)
            )
        } catch (e: DateTimeParseException) {
            invalid(BAD_PERIOD)
        } catch (e: IllegalArgumentException) {
            invalid(BAD_PERIOD)
        }
    }

    private fun string(e: JsonElement?, code: AiReasonCode): String =
        (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid(code)

    private fun nullableString(e: JsonElement?, code: AiReasonCode): String? =
        if (e is JsonNull) null else string(e, code)

    // ── Encoding (the same shape, for the eval set, the API's "How I got this" and recorded replies) ──

    fun encode(plan: AskPlan): JsonObject = when (plan) {
        is AskPlan.Decline -> JsonObject(mapOf("action" to JsonPrimitive("decline"), "reason" to JsonPrimitive(plan.reason.key), "calls" to JsonArray(emptyList())))
        is AskPlan.Tools -> JsonObject(mapOf("action" to JsonPrimitive("tools"), "reason" to JsonNull, "calls" to JsonArray(plan.calls.map(::encode))))
    }

    fun encode(call: AskToolCall): JsonObject {
        val (category, merchant, bucket, limit) = when (call) {
            is AskToolCall.SpendingSummary -> Args()
            is AskToolCall.CategorySpend -> Args(category = call.category)
            is AskToolCall.MerchantSpend -> Args(merchant = call.merchant.name)
            is AskToolCall.TopMerchants -> Args(category = call.category, limit = call.limit)
            is AskToolCall.SpendingTrend -> Args(category = call.category, bucket = call.bucket)
        }
        return JsonObject(mapOf(
            "tool" to JsonPrimitive(call.tool.key),
            "period" to encode(call.period),
            "category" to (category?.let { JsonPrimitive(it.name.lowercase(Locale.ROOT)) } ?: JsonNull),
            "merchant" to (merchant?.let(::JsonPrimitive) ?: JsonNull),
            "bucket" to (bucket?.let { JsonPrimitive(it.name.lowercase(Locale.ROOT)) } ?: JsonNull),
            "limit" to (limit?.let(::JsonPrimitive) ?: JsonNull)
        ))
    }

    fun encode(period: PeriodSpec): JsonObject = JsonObject(mapOf(
        "preset" to JsonPrimitive(period.preset.key),
        "month" to (period.month?.let { JsonPrimitive(it.toString()) } ?: JsonNull),
        "from" to (period.from?.let { JsonPrimitive(it.toString()) } ?: JsonNull),
        "to" to (period.to?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
    ))

    private data class Args(val category: TransactionCategory? = null, val merchant: String? = null, val bucket: Bucket? = null, val limit: Int? = null)
}
