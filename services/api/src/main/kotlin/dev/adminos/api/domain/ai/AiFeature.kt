package dev.adminos.api.domain.ai

/**
 * Each AI feature is its own purpose (D-67, DPDP s.5–6): its own flag, provider, model, redactor, validator,
 * eval record and (AOS-704) consent. A new feature is a new entry here plus one [AiTask] class.
 */
enum class AiFeature(
    /** Stable id in configuration, the audit log, eval records and the API. Never renamed. */
    val key: String,
    /** The model used on Anthropic when `AI_<FEATURE>_MODEL` is not set (D-67: Haiku for extraction, Sonnet for tool use). */
    val defaultAnthropicModel: String
) {
    /** The fallback categoriser for rows the rules left as Other (AOS-512, D-62). */
    CATEGORIZATION("categorization", "claude-haiku-5-5"),

    /** "Ask your money": the model picks read-only analytics tools and words a short answer (AOS-S7.3, D-67). */
    ASK("ask", "claude-sonnet-5-5"),

    /** The weekly briefing worded by a model from its computed facts, kept only if every number is a fact (AOS-716, D-140). */
    BRIEFING("briefing", "claude-haiku-5-5");

    /** Prefix of this feature's settings: `AI_CATEGORIZATION_ENABLED`, `…_PROVIDER`, `…_MODEL`. */
    val envPrefix: String get() = "AI_$name"

    companion object {
        fun fromKey(key: String): AiFeature? = entries.firstOrNull { it.key == key }
    }
}

/** Where a provider runs: on this machine/server, or a vendor's cloud. Shown in the notice (AOS-704). */
enum class AiLocality { LOCAL, CLOUD }

/** The adapters behind [AiProvider]. Test fakes reuse these kinds. */
enum class AiProviderKind(val key: String, val locality: AiLocality) {
    ANTHROPIC("anthropic", AiLocality.CLOUD),
    OLLAMA("ollama", AiLocality.LOCAL);

    companion object {
        fun fromKey(key: String): AiProviderKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A short machine code for why something happened (`http_429`, `not_json_object`, `cap_reached`). Codes are
 * the only free-form value in the audit log, so they are restricted to lowercase letters, digits and `_`:
 * no model output, prompt or message text can fit in one (D-33, D-67).
 */
@JvmInline
value class AiReasonCode(val value: String) {
    init {
        require(value.matches(PATTERN)) { "A reason code is 1..48 of [a-z0-9_]" }
    }

    override fun toString() = value

    companion object {
        private val PATTERN = Regex("[a-z0-9_]{1,48}")
    }
}
