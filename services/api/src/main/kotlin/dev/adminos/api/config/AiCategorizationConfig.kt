package dev.adminos.api.config

/**
 * The optional AI fallback categorizer (AOS-512, D-62). Off unless `AI_CATEGORIZATION_ENABLED=true`, and then
 * `ANTHROPIC_API_KEY` is required: a half-configured setup refuses to start rather than silently doing nothing.
 * The key comes only from the environment and is never printed or logged.
 */
class AiCategorizationConfig(
    val apiKey: String,
    val model: String = DEFAULT_MODEL
) {
    init {
        require(apiKey.isNotBlank()) { "ANTHROPIC_API_KEY is empty" }
        require(model.matches(Regex("[a-z0-9][a-z0-9.\\-]{2,63}"))) { "AI_CATEGORIZATION_MODEL is not a model id" }
    }

    /** Never print the key. */
    override fun toString() = "AiCategorizationConfig(model=$model, apiKey=***)"

    companion object {
        const val DEFAULT_MODEL = "claude-haiku-5-5"

        /** Null when off. Throws (refusing start-up) when enabled without a key, or when the flag is not true/false. */
        fun from(getenv: (String) -> String?): AiCategorizationConfig? {
            fun value(key: String) = getenv(key)?.trim()?.takeIf { it.isNotEmpty() }
            val enabled = when (val flag = value("AI_CATEGORIZATION_ENABLED")?.lowercase()) {
                null, "false" -> false
                "true" -> true
                else -> throw IllegalArgumentException("AI_CATEGORIZATION_ENABLED must be true or false, was '$flag'")
            }
            if (!enabled) return null
            val key = value("ANTHROPIC_API_KEY")
                ?: throw IllegalArgumentException("AI_CATEGORIZATION_ENABLED=true needs ANTHROPIC_API_KEY (or set it to false)")
            return AiCategorizationConfig(key, value("AI_CATEGORIZATION_MODEL") ?: DEFAULT_MODEL)
        }
    }
}
