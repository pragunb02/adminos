package dev.adminos.api.domain.ai

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The one way an [AiTask.read] turns a model's reply into JSON (AOS-635). A model server is untrusted input: the
 * JSON parser recurses once per `[`/`{`, so `"[" × 10,000` would throw `StackOverflowError`. The reply's size and
 * nesting depth are checked first, in one linear pass, and only a bounded reply is parsed.
 * The same approach as AOS-705's `ReadMessageTask`.
 */
object AiJson {
    /** Far above any answer a task's schema allows (they are a few hundred characters). */
    const val MAX_REPLY_CHARS = 16_384

    /**
     * @param maxDepth how many `{`/`[` levels may be open at once, outside strings (1 = a flat object)
     * @return the reply as a JSON object, or null when it is too big, too deep, not JSON, or not an object
     */
    fun parseObject(text: String, maxDepth: Int, maxChars: Int = MAX_REPLY_CHARS): JsonObject? {
        require(maxDepth >= 1) { "A JSON object is at least one level deep" }
        if (text.length > maxChars || depthExceeds(text, maxDepth)) return null
        return try {
            Json.parseToJsonElement(text.trim()) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** True if more than [maxDepth] brackets are open at any point, ignoring brackets inside JSON strings. */
    internal fun depthExceeds(text: String, maxDepth: Int): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' || c == '[' -> if (++depth > maxDepth) return true
                c == '}' || c == ']' -> depth--
            }
        }
        return false
    }
}
