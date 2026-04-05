package dev.adminos.api.infrastructure.database

import kotlinx.serialization.json.*
import java.util.Base64

/**
 * Encodes/decodes cursor-based pagination tokens as Base64 JSON.
 * Format: {"key":"<sortKey>","dir":"<asc|desc>"}
 */
object CursorCodec {
    fun encode(sortKey: String, direction: String = "desc"): String {
        val jsonObj = buildJsonObject {
            put("key", sortKey)
            put("dir", direction)
        }
        return Base64.getEncoder().encodeToString(jsonObj.toString().toByteArray())
    }

    fun decode(cursor: String): Pair<String, String> {
        val json = String(Base64.getDecoder().decode(cursor))
        val obj = Json.parseToJsonElement(json).jsonObject
        return obj["key"]!!.jsonPrimitive.content to
               obj["dir"]!!.jsonPrimitive.content
    }
}
