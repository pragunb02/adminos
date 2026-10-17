package dev.adminos.api.domain.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The test adapter for the [AiProvider] port (AOS-702): answers with [reply] and records every request,
 * so tests can check exactly what would have left AdminOS. Priced like a cloud model unless told otherwise.
 */
class FakeAiProvider(
    override val kind: AiProviderKind = AiProviderKind.ANTHROPIC,
    private val prices: Map<String, AiPrice> = mapOf(MODEL to AiPrice.usd("1.00", "2.00")),
    private val reply: suspend (AiRequest) -> AiCompletion = { AiCompletion.Completed("ok", AiUsage(10, 5)) }
) : AiProvider {
    val requests: MutableList<AiRequest> = CopyOnWriteArrayList()

    override fun price(model: String): AiPrice? = prices[model]

    override suspend fun complete(request: AiRequest): AiCompletion {
        requests += request
        return reply(request)
    }

    companion object {
        const val MODEL = "fake-model-1"
    }
}

/** A minimal feature task: sends the input as is (unless it says "secret"), accepts "ok" and nothing else. */
object EchoTask : AiTask<String, String> {
    override val feature = AiFeature.CATEGORIZATION
    override val promptVersion = "echo-v1"
    val SCHEMA = JsonObject(mapOf("type" to JsonPrimitive("object")))

    override fun prompt(input: String): AiPrompt =
        if ("secret" in input) AiPrompt.Withhold(AiReasonCode("secret_in_input")) else AiPrompt.Send("Echo the input.", input, SCHEMA, 100)

    override fun read(text: String, input: String): AiReading<String> =
        if (text == "ok") AiReading.Valid("ok:$input") else AiReading.Invalid(AiReasonCode("not_ok"))
}
