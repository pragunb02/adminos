package dev.adminos.api.domain.ai

import kotlinx.serialization.json.JsonObject

/**
 * One AI feature's contract with the gateway (D-67): what it sends and what it accepts.
 * - [prompt] is the feature's **redactor**: it builds the only text that leaves AdminOS, or withholds the
 *   input entirely. It is a pure function, unit-tested per feature.
 * - [read] is the feature's **validator**: the model's text becomes a typed value only if it passes every
 *   check (schema again, closed lists, grounding in the input); anything else is rejected with a code.
 * - [promptVersion] names the prompt and schema; changing either needs a new version and a new eval record.
 */
interface AiTask<I, O> {
    val feature: AiFeature
    val promptVersion: String

    fun prompt(input: I): AiPrompt

    fun read(text: String, input: I): AiReading<O>
}

/** What a task's redactor decided. */
sealed interface AiPrompt {
    /** Send exactly this. [user] is already redacted. */
    data class Send(val system: String, val user: String, val schema: JsonObject, val maxOutputTokens: Int) : AiPrompt {
        override fun toString() = "Send(maxOutputTokens=$maxOutputTokens)" // never print the prompt
    }

    /** Don't send anything: the input holds data that may not leave (e.g. a phone number in a name). */
    data class Withhold(val code: AiReasonCode) : AiPrompt
}

/** What a task's validator made of the model's text. */
sealed interface AiReading<out O> {
    data class Valid<O>(val value: O) : AiReading<O>

    data class Invalid(val code: AiReasonCode) : AiReading<Nothing>
}

/** Why the gateway didn't ask at all. */
enum class AiRefusal(val code: AiReasonCode) {
    /** The feature isn't enabled on this server. */
    FEATURE_OFF(AiReasonCode("feature_off")),

    /** The task's redactor withheld the input. */
    WITHHELD(AiReasonCode("withheld")),

    /** This month's spend would pass the cap (fails closed). */
    CAP_REACHED(AiReasonCode("cap_reached"))
}

/** What [AiGateway.run] gives a feature. Only [Answered] carries a value. */
sealed interface AiResult<out O> {
    /** A validated answer from [model]. */
    data class Answered<O>(val value: O, val model: String) : AiResult<O>

    /** The model answered, but the answer failed validation, was refused or was cut off. */
    data class Rejected(val code: AiReasonCode) : AiResult<Nothing>

    /** No answer this time (provider down, rate limited, circuit open); worth asking again later. */
    data class Unavailable(val code: AiReasonCode) : AiResult<Nothing>

    /** Not asked: see [AiRefusal]. */
    data class Refused(val reason: AiRefusal) : AiResult<Nothing>
}
