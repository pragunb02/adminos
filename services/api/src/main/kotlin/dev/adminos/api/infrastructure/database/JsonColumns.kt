package dev.adminos.api.infrastructure.database

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import java.math.BigDecimal

/**
 * The single mapper between domain JSON-ish values (`Map<String, Any?>`, lists of maps) and the
 * text stored in JSONB columns. Used by every Exposed repository (docs/ENGINEERING.md §6, mappers).
 *
 * Supported values: null, String, Boolean, Number (Int/Long/Double/BigDecimal), Map<String, *>, Iterable.
 * Anything else is rejected loudly rather than stringified (no silent fallbacks).
 */
@OptIn(ExperimentalSerializationApi::class) // JsonUnquotedLiteral
object JsonColumns {

    private val json = Json

    fun encode(value: Map<String, Any?>): String = json.encodeToString(JsonObject.serializer(), toJsonObject(value))

    fun encodeList(values: List<Map<String, Any?>>): String =
        json.encodeToString(JsonArray.serializer(), JsonArray(values.map { toJsonObject(it) }))

    fun decode(text: String): Map<String, Any?> = fromJsonObject(json.parseToJsonElement(text).jsonObject)

    fun decodeList(text: String): List<Map<String, Any?>> =
        json.parseToJsonElement(text).jsonArray.map { fromJsonObject(it.jsonObject) }

    private fun toJsonObject(map: Map<String, Any?>): JsonObject = JsonObject(map.mapValues { (_, v) -> toJsonElement(v) })

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        // Exact decimal text: JsonPrimitive(Number) goes through Double, which loses scale and precision (money!)
        is BigDecimal -> JsonUnquotedLiteral(value.toPlainString())
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) ->
            require(k is String) { "JSON object keys must be strings, got ${k?.let { it::class.simpleName }}" }
            k to toJsonElement(v)
        })
        is Iterable<*> -> JsonArray(value.map { toJsonElement(it) })
        else -> throw IllegalArgumentException("Unsupported JSON column value type: ${value::class.qualifiedName}")
    }

    private fun fromJsonObject(obj: JsonObject): Map<String, Any?> = obj.mapValues { (_, v) -> fromJsonElement(v) }

    private fun fromJsonElement(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonObject -> fromJsonObject(element)
        is JsonArray -> element.map { fromJsonElement(it) }
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.boolean
            element.longOrNull != null -> element.long
            else -> BigDecimal(element.content)
        }
    }
}
