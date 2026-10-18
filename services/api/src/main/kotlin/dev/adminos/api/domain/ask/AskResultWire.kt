package dev.adminos.api.domain.ask

import dev.adminos.api.domain.ai.SafeName
import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.common.formatInr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * Tool results as a model is shown them (AOS-710; also the eval's dump, AOS-712). Minimum data (D-67):
 * - only aggregates: totals, counts and per-category, per-merchant or per-bucket sums; never a transaction, a
 *   narration, an account or a payment handle;
 * - amounts are formatted the way the answer should write them (`₹1,23,456.50`), so a model can copy them exactly
 *   and the number check (see [AnswerGrounding]) can match them;
 * - a merchant name is shown only if [SafeName] allows it; otherwise it is [HIDDEN_NAME] (it may carry a phone
 *   number, hidden characters or text from a hostile SMS). The user still sees the real name in "How I got this".
 * Each result has an id `t1`, `t2`… in plan order; comparisons are computed here, not by the model.
 */
object AskResultWire {
    const val HIDDEN_NAME = "(name hidden)"

    fun id(index: Int) = "t${index + 1}"

    fun forModel(results: List<AskToolResult>, comparisons: List<AskComparison> = AskComparison.of(results)): JsonObject =
        JsonObject(mapOf(
            "results" to JsonArray(results.mapIndexed { i, r -> result(id(i), r) }),
            "comparisons" to JsonArray(comparisons.map(::comparison))
        ))

    /** A merchant name as the model may see it. */
    fun visibleName(name: String): String = if (SafeName.isSafe(name)) name else HIDDEN_NAME

    private fun result(id: String, r: AskToolResult): JsonObject {
        val fields = linkedMapOf<String, JsonElement>(
            "id" to JsonPrimitive(id),
            "tool" to JsonPrimitive(r.call.tool.key),
            "period" to period(r.range)
        )
        when (r) {
            is AskToolResult.Summary -> {
                fields["spent"] = money(r.spent); fields["received"] = money(r.received); fields["payments"] = JsonPrimitive(r.payments)
            }
            is AskToolResult.Categories -> {
                fields["category"] = r.call.category?.let { JsonPrimitive(key(it.name)) } ?: JsonNull
                fields["rows"] = JsonArray(r.rows.map { JsonObject(mapOf("category" to JsonPrimitive(key(it.category.name)), "spent" to money(it.spent), "payments" to JsonPrimitive(it.payments))) })
                fields["total"] = money(r.total)
            }
            is AskToolResult.Merchant -> {
                fields["merchantAsked"] = JsonPrimitive(visibleName(r.call.merchant.name))
                fields["rows"] = merchants(r.rows)
                fields["total"] = money(r.total)
            }
            is AskToolResult.TopMerchants -> {
                fields["category"] = r.call.category?.let { JsonPrimitive(key(it.name)) } ?: JsonNull
                fields["limit"] = JsonPrimitive(r.call.limit)
                fields["rows"] = merchants(r.rows)
            }
            is AskToolResult.Trend -> {
                fields["bucket"] = JsonPrimitive(key(r.call.bucket.name))
                fields["category"] = r.call.category?.let { JsonPrimitive(key(it.name)) } ?: JsonNull
                fields["points"] = JsonArray(r.points.map { JsonObject(mapOf("start" to JsonPrimitive(it.start.toString()), "spent" to money(it.spent))) })
                fields["total"] = money(r.total)
            }
        }
        return JsonObject(fields)
    }

    private fun merchants(rows: List<MerchantLine>) = JsonArray(rows.map {
        JsonObject(mapOf("merchant" to JsonPrimitive(visibleName(it.name)), "spent" to money(it.spent), "payments" to JsonPrimitive(it.payments), "category" to JsonPrimitive(key(it.category.name))))
    })

    private fun comparison(c: AskComparison) = JsonObject(mapOf(
        "later" to JsonPrimitive(id(c.later)),
        "earlier" to JsonPrimitive(id(c.earlier)),
        "difference" to money(c.difference),
        "changePct" to (c.changePct?.let { JsonPrimitive(it.toPlainString() + "%") } ?: JsonNull)
    ))

    private fun period(range: DateRange) = JsonObject(mapOf("from" to JsonPrimitive(range.from.toString()), "to" to JsonPrimitive(range.to.toString())))

    private fun money(amount: java.math.BigDecimal) = JsonPrimitive(amount.formatInr())

    private fun key(name: String) = name.lowercase(Locale.ROOT)
}
