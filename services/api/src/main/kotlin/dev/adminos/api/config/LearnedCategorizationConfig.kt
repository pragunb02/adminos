package dev.adminos.api.config

/**
 * Learning categories from the user's own corrections (AOS-707, D-67). Off unless `LEARNED_CATEGORIZATION_ENABLED=true`:
 * every smart feature is its own switch, off by default (D-67). The model runs inside the API and sends nothing
 * anywhere, so turning it on needs no key. Any value other than true/false refuses to start.
 */
object LearnedCategorizationConfig {
    const val FLAG = "LEARNED_CATEGORIZATION_ENABLED"

    fun enabled(getenv: (String) -> String?): Boolean = when (val flag = getenv(FLAG)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }) {
        null, "false" -> false
        "true" -> true
        else -> throw IllegalArgumentException("$FLAG must be true or false, was '$flag'")
    }
}
