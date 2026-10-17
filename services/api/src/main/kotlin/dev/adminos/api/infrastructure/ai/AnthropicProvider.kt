package dev.adminos.api.infrastructure.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.ThinkingConfigDisabled
import dev.adminos.api.domain.ai.AiCompletion
import dev.adminos.api.domain.ai.AiPrice
import dev.adminos.api.domain.ai.AiProvider
import dev.adminos.api.domain.ai.AiProviderKind
import dev.adminos.api.domain.ai.AiReasonCode
import dev.adminos.api.domain.ai.AiRequest
import dev.adminos.api.domain.ai.AiUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Duration

/**
 * Claude through the official Java SDK (AOS-512's client, generalised in AOS-702): one Messages API call with
 * structured output (`output_config.format` = the task's JSON schema), low effort, and thinking off where
 * the model allows it. The SDK retries 408/409/429/5xx and connection errors with exponential backoff
 * ([MAX_RETRIES]); each attempt times out after [TIMEOUT]. Errors carry statuses only: the key, the prompt and
 * the reply are never logged.
 */
class AnthropicProvider(private val client: AnthropicClient) : AiProvider {
    override val kind = AiProviderKind.ANTHROPIC

    override fun price(model: String): AiPrice? = MODELS[model]?.price

    override suspend fun complete(request: AiRequest): AiCompletion = withContext(Dispatchers.IO) {
        val message = try {
            client.messages().create(params(request))
        } catch (e: AnthropicServiceException) {
            return@withContext AiCompletion.Failed(AiReasonCode("http_${e.statusCode()}"))
        } catch (e: AnthropicIoException) {
            return@withContext AiCompletion.Failed(NETWORK)
        } catch (e: AnthropicException) {
            return@withContext AiCompletion.Failed(CLIENT)
        }
        read(message)
    }

    internal fun params(request: AiRequest): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(request.model)
            .maxTokens(request.maxOutputTokens.toLong())
            .system(request.system)
            .outputConfig(OutputConfig.builder()
                .effort(OutputConfig.Effort.LOW)
                .format(JsonOutputFormat.builder().schema(schema(request.schema)).build())
                .build())
            .addUserMessage(request.user)
        // Claude Haiku 5.5 allows thinking off at effort ≤ high; Sonnet/Opus 5.5 reject it, so they keep the
        // default (adaptive) at low effort.
        if (MODELS[request.model]?.thinkingCanBeDisabled == true) builder.thinking(ThinkingConfigDisabled.builder().build())
        return builder.build()
    }

    /** A model this adapter knows: its price, and whether thinking may be turned off. */
    internal data class Model(val price: AiPrice, val thinkingCanBeDisabled: Boolean)

    companion object {
        const val MAX_RETRIES = 3
        val TIMEOUT: Duration = Duration.ofSeconds(20)
        private val NETWORK = AiReasonCode("network")
        private val CLIENT = AiReasonCode("client")

        /**
         * Anthropic first-party prices, US$ per million tokens (checked 2026-10-17). A model missing here is
         * refused at start-up, because the cost cap can't hold for a call it can't price.
         */
        internal val MODELS: Map<String, Model> = mapOf(
            "claude-haiku-5-5" to Model(
                AiPrice.usd("0.10", "0.50").copy(longPrompt = AiPrice.usd("0.50", "2.50"), longPromptFromTokens = 100_000),
                thinkingCanBeDisabled = true
            ),
            "claude-sonnet-5-5" to Model(AiPrice.usd("2.00", "10.00"), thinkingCanBeDisabled = false),
            "claude-opus-5-5" to Model(AiPrice.usd("4.00", "20.00"), thinkingCanBeDisabled = false)
        )

        internal fun read(message: Message): AiCompletion {
            val usage = message.usage().let { AiUsage(it.inputTokens(), it.outputTokens()) }
            when (message.stopReason().orElse(null)) {
                StopReason.REFUSAL -> return AiCompletion.Refused(usage)
                StopReason.MAX_TOKENS -> return AiCompletion.Truncated(usage)
                else -> Unit
            }
            val text = message.content().flatMap { block -> block.text().map { listOf(it.text()) }.orElse(emptyList()) }.joinToString("")
            return AiCompletion.Completed(text, usage)
        }

        internal fun schema(schema: JsonObject): JsonOutputFormat.Schema {
            val builder = JsonOutputFormat.Schema.builder()
            schema.forEach { (key, value) -> builder.putAdditionalProperty(key, JsonValue.from(plain(value))) }
            return builder.build()
        }

        /** A kotlinx JSON value as the plain maps, lists and scalars the SDK's [JsonValue.from] takes. */
        internal fun plain(element: JsonElement): Any? = when (element) {
            is JsonNull -> null
            is JsonObject -> element.mapValues { plain(it.value) }
            is JsonArray -> element.map { plain(it) }
            is JsonPrimitive -> when {
                element.isString -> element.content
                element.booleanOrNull != null -> element.booleanOrNull
                element.longOrNull != null -> element.longOrNull
                else -> element.doubleOrNull
            }
        }

        /** The provider for [apiKey]; [baseUrl] only for tests against a local server. */
        fun create(apiKey: String, baseUrl: String? = null, maxRetries: Int = MAX_RETRIES, timeout: Duration = TIMEOUT): AnthropicProvider {
            require(apiKey.isNotBlank()) { "ANTHROPIC_API_KEY is empty" }
            val builder = AnthropicOkHttpClient.builder().apiKey(apiKey).maxRetries(maxRetries).timeout(timeout)
            baseUrl?.let { builder.baseUrl(it) }
            return AnthropicProvider(builder.build())
        }
    }
}
