package dev.adminos.api.domain.ai

import kotlinx.serialization.json.JsonObject

/**
 * The one port every model call goes through (D-67, AOS-702). Adapters live in `infrastructure/ai`
 * (Anthropic via the official SDK, Ollama via HTTP) and in tests (fakes). An adapter only moves text:
 * it never decides what may be sent (the feature's [AiTask] does), never retries beyond its client's own
 * backoff, and never throws for a vendor error: every failure is an [AiCompletion.Failed] with a code.
 */
interface AiProvider {
    val kind: AiProviderKind

    /** The price of [model] on this provider, or null when unknown (start-up then refuses that model). */
    fun price(model: String): AiPrice?

    /** One schema-constrained completion. */
    suspend fun complete(request: AiRequest): AiCompletion
}

/**
 * One request to a model: a fixed system prompt, the redacted user text, and the JSON schema the answer
 * must follow (structured outputs on Claude, `format` on Ollama).
 */
data class AiRequest(
    val model: String,
    val system: String,
    val user: String,
    val schema: JsonObject,
    val maxOutputTokens: Int
) {
    init {
        require(model.isNotBlank()) { "A request names its model" }
        require(system.isNotBlank() && user.isNotBlank()) { "A request has a system prompt and user text" }
        require(maxOutputTokens in 1..MAX_OUTPUT) { "maxOutputTokens is 1..$MAX_OUTPUT" }
    }

    /**
     * An upper bound on the input tokens, for the cost reservation: every token is at least one UTF-8 byte,
     * so the byte count can't be exceeded, whatever the language or script.
     */
    val inputTokenBound: Long
        get() = (system.toByteArray().size + user.toByteArray().size + schema.toString().toByteArray().size).toLong() + ENVELOPE

    // Never print the prompt (it can hold user data, even redacted).
    override fun toString() = "AiRequest(model=$model, maxOutputTokens=$maxOutputTokens)"

    companion object {
        const val MAX_OUTPUT = 16_000
        /** Role markers and message framing a provider adds around the text. */
        private const val ENVELOPE = 64L
    }
}

/** Tokens a call used, as the provider reported them. */
data class AiUsage(val inputTokens: Long, val outputTokens: Long) {
    init {
        require(inputTokens >= 0 && outputTokens >= 0) { "Token counts are not negative" }
    }

    companion object {
        val NONE = AiUsage(0, 0)
    }
}

/** What a provider returned. Only [Completed] carries text, which only the feature's validator reads. */
sealed interface AiCompletion {
    val usage: AiUsage

    data class Completed(val text: String, override val usage: AiUsage) : AiCompletion {
        override fun toString() = "Completed(${text.length} chars, $usage)" // never print model output
    }

    /** The model declined to answer. */
    data class Refused(override val usage: AiUsage) : AiCompletion

    /** The answer hit the output limit and is incomplete. */
    data class Truncated(override val usage: AiUsage) : AiCompletion

    /** No answer: network, HTTP status, a bad reply or a client error, after the client's own retries. */
    data class Failed(val code: AiReasonCode, override val usage: AiUsage = AiUsage.NONE) : AiCompletion
}

/**
 * A model's price in micro-dollars (US$ 1e-6) per million tokens. Money is never a Double (ENGINEERING §2).
 * [longPrompt] is the higher rate some models charge once the input exceeds [longPromptFromTokens].
 */
data class AiPrice(
    val inputMicrosPerMTok: Long,
    val outputMicrosPerMTok: Long,
    val longPrompt: AiPrice? = null,
    val longPromptFromTokens: Long = Long.MAX_VALUE
) {
    init {
        require(inputMicrosPerMTok >= 0 && outputMicrosPerMTok >= 0) { "Prices are not negative" }
        require(longPromptFromTokens > 0) { "The long-prompt threshold is positive" }
        require(longPrompt?.longPrompt == null) { "Only one long-prompt tier" }
    }

    /** The cost of [usage], rounded up to a whole micro-dollar. */
    fun costMicros(usage: AiUsage): Long {
        val rate = if (usage.inputTokens > longPromptFromTokens) longPrompt ?: this else this
        val total = Math.addExact(Math.multiplyExact(usage.inputTokens, rate.inputMicrosPerMTok), Math.multiplyExact(usage.outputTokens, rate.outputMicrosPerMTok))
        return Math.ceilDiv(total, PER_MILLION)
    }

    /** The most [request] can cost: its input bound plus every output token it may produce. */
    fun worstCaseMicros(request: AiRequest): Long = costMicros(AiUsage(request.inputTokenBound, request.maxOutputTokens.toLong()))

    val isFree: Boolean get() = this == FREE

    companion object {
        private const val PER_MILLION = 1_000_000L
        val FREE = AiPrice(0, 0)

        /** From dollars per million tokens, e.g. `usd("0.10", "0.50")`. */
        fun usd(inputPerMTok: String, outputPerMTok: String) = AiPrice(Micros.ofUsd(inputPerMTok), Micros.ofUsd(outputPerMTok))
    }
}

/** Micro-dollar conversions, exact (BigDecimal). */
object Micros {
    private val MILLION = java.math.BigDecimal(1_000_000)

    /** "2.50" → 2_500_000. More than 6 decimal places is refused rather than rounded. */
    fun ofUsd(usd: String): Long {
        val value = usd.trim().toBigDecimalOrNull() ?: throw IllegalArgumentException("'$usd' is not an amount in dollars")
        require(value.signum() >= 0) { "A dollar amount is not negative" }
        return value.multiply(MILLION).longValueExact()
    }

    /** 2_500_000 → "2.50"; at least 2 decimals, more only when needed (0.000123). */
    fun toUsd(micros: Long): String {
        val value = java.math.BigDecimal(micros).divide(MILLION).stripTrailingZeros()
        return if (value.scale() < 2) value.setScale(2).toPlainString() else value.toPlainString()
    }
}
