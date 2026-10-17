package dev.adminos.api.infrastructure.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.adminos.api.domain.ai.AiCompletion
import dev.adminos.api.domain.ai.AiPrice
import dev.adminos.api.domain.ai.AiProvider
import dev.adminos.api.domain.ai.AiProviderKind
import dev.adminos.api.domain.ai.AiReasonCode
import dev.adminos.api.domain.ai.AiRequest
import dev.adminos.api.domain.ai.AiUsage
import kotlinx.coroutines.future.await
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * A local model through Ollama's HTTP API (`POST /api/chat`, AOS-702). Nothing leaves the machine or the
 * compose network. The task's JSON schema goes in `format`, so Ollama constrains decoding to it; the
 * temperature is 0 so answers repeat, and thinking is off (`think: false`) so the output budget goes to the
 * answer. Local calls cost nothing ([AiPrice.FREE]), so the cost cap never blocks them; they are still
 * audited. No retries here: a local server that is down stays down, and the circuit breaker and the next
 * scheduled run handle it.
 */
class OllamaProvider(
    private val baseUrl: URI,
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
) : AiProvider {
    override val kind = AiProviderKind.OLLAMA

    init {
        require(baseUrl.scheme in setOf("http", "https") && !baseUrl.host.isNullOrBlank()) { "OLLAMA_BASE_URL must be an http(s) URL with a host" }
        require(!timeout.isNegative && !timeout.isZero) { "The Ollama timeout is positive" }
    }

    /** Every local model is free to call. */
    override fun price(model: String): AiPrice = AiPrice.FREE

    override suspend fun complete(request: AiRequest): AiCompletion {
        val response = try {
            http.sendAsync(httpRequest(request), HttpResponse.BodyHandlers.ofInputStream()).await()
        } catch (e: HttpTimeoutException) {
            return AiCompletion.Failed(TIMEOUT)
        } catch (e: IOException) {
            return AiCompletion.Failed(NETWORK)
        }
        // A model server is not trusted to keep its reply small or quick: read at most MAX_REPLY_BYTES, off the
        // caller's thread, and close the stream if the body takes longer than the request timeout (D-100).
        val body = response.body()
        var timedOut = false
        val bytes = try {
            coroutineScope {
                val watchdog = launch { delay(timeout.toMillis()); timedOut = true; body.close() }
                try {
                    withContext(Dispatchers.IO) { body.use { it.readNBytes(MAX_REPLY_BYTES + 1) } }
                } finally {
                    watchdog.cancel()
                }
            }
        } catch (e: IOException) {
            return AiCompletion.Failed(if (timedOut) TIMEOUT else NETWORK)
        }
        if (response.statusCode() != 200) return AiCompletion.Failed(AiReasonCode("http_${response.statusCode()}"))
        if (bytes.size > MAX_REPLY_BYTES) return AiCompletion.Failed(REPLY_TOO_LARGE)
        return read(bytes.toString(Charsets.UTF_8))
    }

    internal fun httpRequest(request: AiRequest): HttpRequest = HttpRequest.newBuilder(URI.create(baseUrl.toString().trimEnd('/') + "/api/chat"))
        .timeout(timeout)
        .header("content-type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body(request).toString()))
        .build()

    companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(120)
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val TIMEOUT = AiReasonCode("timeout")
        private val NETWORK = AiReasonCode("network")
        private val BAD_REPLY = AiReasonCode("bad_reply")
        private val REPLY_TOO_LARGE = AiReasonCode("reply_too_large")
        /** Far above any structured answer we ask for (a few hundred bytes). */
        internal const val MAX_REPLY_BYTES = 256 * 1024

        internal fun body(request: AiRequest): JsonObject = JsonObject(mapOf(
            "model" to JsonPrimitive(request.model),
            "messages" to JsonArray(listOf(
                JsonObject(mapOf("role" to JsonPrimitive("system"), "content" to JsonPrimitive(request.system))),
                JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to JsonPrimitive(request.user)))
            )),
            "format" to request.schema,
            "stream" to JsonPrimitive(false),
            "think" to JsonPrimitive(false),
            "options" to JsonObject(mapOf("temperature" to JsonPrimitive(0), "num_predict" to JsonPrimitive(request.maxOutputTokens)))
        ))

        /** Ollama's non-streaming chat reply: `message.content`, `done_reason`, and token counts. */
        internal fun read(body: String): AiCompletion {
            val obj = try {
                Json.parseToJsonElement(body) as? JsonObject
            } catch (e: SerializationException) {
                null
            } ?: return AiCompletion.Failed(BAD_REPLY)
            val text = (obj["message"] as? JsonObject)?.get("content")?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
                ?: return AiCompletion.Failed(BAD_REPLY)
            val usage = AiUsage(count(obj, "prompt_eval_count"), count(obj, "eval_count"))
            return when ((obj["done_reason"] as? JsonPrimitive)?.contentOrNull) {
                "length" -> AiCompletion.Truncated(usage)
                else -> AiCompletion.Completed(text, usage)
            }
        }

        private fun count(obj: JsonObject, key: String): Long = (obj[key] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0) ?: 0
    }
}
