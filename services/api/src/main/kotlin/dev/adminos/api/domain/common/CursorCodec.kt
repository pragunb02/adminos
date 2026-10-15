package dev.adminos.api.domain.common

import kotlinx.serialization.json.*
import java.util.Base64

/**
 * The API's opaque pagination cursor: Base64 JSON `{"key":"<sortKey>","dir":"<asc|desc>"}`.
 * Routes encode it, and every repository implementation (in-memory and Exposed) decodes it, so
 * both behave the same (Liskov, docs/ENGINEERING.md §5).
 */
object CursorCodec {
    fun encode(sortKey: String, direction: String = "desc"): String {
        val jsonObj = buildJsonObject {
            put("key", sortKey)
            put("dir", direction)
        }
        return Base64.getEncoder().encodeToString(jsonObj.toString().toByteArray())
    }

    /** @throws IllegalArgumentException (mapped to 400) for a malformed cursor */
    fun decode(cursor: String): Pair<String, String> {
        val obj = runCatching { Json.parseToJsonElement(String(Base64.getDecoder().decode(cursor))).jsonObject }
            .getOrElse { throw IllegalArgumentException("Invalid pagination cursor") }
        val key = obj["key"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Invalid pagination cursor")
        return key to (obj["dir"]?.jsonPrimitive?.content ?: "desc")
    }

    /** Sort key of a cursor (the part repositories filter on). */
    fun key(cursor: String): String = decode(cursor).first
}
