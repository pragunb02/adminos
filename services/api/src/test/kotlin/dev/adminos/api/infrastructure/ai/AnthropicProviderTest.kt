package dev.adminos.api.infrastructure.ai

import com.sun.net.httpserver.HttpServer
import dev.adminos.api.domain.ai.AiCallOutcome
import dev.adminos.api.domain.ai.AiCompletion
import dev.adminos.api.domain.ai.AiCostGuard
import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.domain.ai.AiGateway
import dev.adminos.api.domain.ai.AiPrice
import dev.adminos.api.domain.ai.AiPrompt
import dev.adminos.api.domain.ai.AiReasonCode
import dev.adminos.api.domain.ai.AiRequest
import dev.adminos.api.domain.ai.AiRoute
import dev.adminos.api.domain.ai.AiUsage
import dev.adminos.api.domain.ai.InMemoryAiCallLogRepository
import dev.adminos.api.domain.ai.InMemoryAiSpendLedger
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.ai.AI_ALLOWED_CATEGORIES
import dev.adminos.api.domain.financial.ai.AiCategoryAnswer
import dev.adminos.api.domain.financial.ai.AiCategoryQuestion
import dev.adminos.api.domain.financial.ai.AiCategoryResult
import dev.adminos.api.domain.financial.ai.CategorizeMerchantTask
import dev.adminos.api.domain.financial.ai.PortAiCategoryClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AOS-512 / AOS-702: the Claude adapter against a local fake of the Messages API (no real network, no real key).
 * Checks what is sent, how replies are read, how failures are reported, and that the categoriser on the
 * port behaves as AOS-512 did.
 */
class AnthropicProviderTest {

    private data class Reply(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())
    private data class Seen(val path: String, val headers: Map<String, String>, val body: String)

    private val seen = CopyOnWriteArrayList<Seen>()
    private val replies = ArrayDeque<Reply>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            seen += Seen(ex.requestURI.path, ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") },
                ex.requestBody.readBytes().decodeToString())
            val reply = synchronized(replies) { replies.removeFirstOrNull() } ?: Reply(500, """{"type":"error","error":{"type":"api_error","message":"no reply"}}""")
            reply.headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            ex.responseHeaders.add("content-type", "application/json")
            val bytes = reply.body.toByteArray()
            ex.sendResponseHeaders(reply.status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    // Generated at runtime: never a literal key in the repo.
    private val key = "test-" + UUID.randomUUID()
    private fun provider(retries: Int = 0) =
        AnthropicProvider.create(key, baseUrl = "http://127.0.0.1:${server.address.port}", maxRetries = retries, timeout = Duration.ofSeconds(5))

    private val question = AiCategoryQuestion("Steam", PaymentMethod.CARD_CREDIT, AI_ALLOWED_CATEGORIES)

    private fun request(q: AiCategoryQuestion = question, model: String = "claude-haiku-5-5"): AiRequest {
        val p = CategorizeMerchantTask.prompt(q) as AiPrompt.Send
        return AiRequest(model, p.system, p.user, p.schema, p.maxOutputTokens)
    }

    private fun message(text: String, stop: String = "end_turn") = """
        {"id":"msg_test","type":"message","role":"assistant","model":"claude-haiku-5-5",
         "content":[{"type":"text","text":${JsonPrimitive(text)}}],
         "stop_reason":"$stop","stop_sequence":null,"usage":{"input_tokens":120,"output_tokens":20}}
    """.trimIndent()

    private val good = """{"category":"ENTERTAINMENT","confidence":0.9,"reason":"PC game store"}"""
    private val usage = AiUsage(120, 20)

    @Test
    fun `a reply is returned as text with its token usage`() = runBlocking<Unit> {
        replies += Reply(200, message(good))
        assertEquals(AiCompletion.Completed(good, usage), provider().complete(request()))
    }

    @Test
    fun `only the task's prompt, schema and settings are sent`() = runBlocking<Unit> {
        replies += Reply(200, message(good))
        provider().complete(request())
        val sent = seen.single()
        assertEquals("/v1/messages", sent.path)
        assertEquals(key, sent.headers["x-api-key"])
        assertTrue(sent.headers.containsKey("anthropic-version"))

        val body = Json.parseToJsonElement(sent.body).jsonObject
        assertEquals("claude-haiku-5-5", body["model"]?.jsonPrimitive?.content)
        assertEquals("300", body["max_tokens"]?.jsonPrimitive?.content)
        assertEquals("disabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("low", body["output_config"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        val format = body.getValue("output_config").jsonObject.getValue("format").jsonObject
        assertEquals("json_schema", format["type"]?.jsonPrimitive?.content)
        assertEquals(CategorizeMerchantTask.schema(AI_ALLOWED_CATEGORIES), format["schema"])

        val messages = body.getValue("messages").jsonArray
        assertEquals(1, messages.size)
        val content = messages[0].jsonObject.getValue("content")
        val text = if (content is JsonPrimitive) content.content else content.jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content
        val user = Json.parseToJsonElement(text).jsonObject
        assertEquals(setOf("merchant", "paymentMethod", "allowedCategories"), user.keys)
        assertEquals("Steam", user["merchant"]?.jsonPrimitive?.content)
        assertEquals("CARD_CREDIT", user["paymentMethod"]?.jsonPrimitive?.content)
    }

    @Test
    fun `models that reject disabled thinking keep the default and low effort`() = runBlocking<Unit> {
        replies += Reply(200, message(good))
        provider().complete(request(model = "claude-sonnet-5-5"))
        val body = Json.parseToJsonElement(seen.single().body).jsonObject
        assertNull(body["thinking"])
        assertEquals("low", body["output_config"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `a refusal or a truncated reply is reported as such with its usage`() = runBlocking<Unit> {
        replies += Reply(200, message("", stop = "refusal"))
        replies += Reply(200, message("{\"category\":", stop = "max_tokens"))
        assertEquals(AiCompletion.Refused(usage), provider().complete(request()))
        assertEquals(AiCompletion.Truncated(usage), provider().complete(request()))
    }

    @Test
    fun `a server error is retried with backoff, then answered`() = runBlocking<Unit> {
        replies += Reply(529, """{"type":"error","error":{"type":"overloaded_error","message":"busy"}}""", mapOf("retry-after-ms" to "10"))
        replies += Reply(200, message(good))
        assertIs<AiCompletion.Completed>(provider(retries = 1).complete(request()))
        assertEquals(2, seen.size)
    }

    @Test
    fun `a rate limit that outlasts the retries fails with its status only`() = runBlocking<Unit> {
        repeat(2) { replies += Reply(429, """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}""", mapOf("retry-after-ms" to "10")) }
        assertEquals(AiCompletion.Failed(AiReasonCode("http_429")), provider(retries = 1).complete(request()))
        assertEquals(2, seen.size)
    }

    @Test
    fun `a bad key fails and is not retried`() = runBlocking<Unit> {
        replies += Reply(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        assertEquals(AiCompletion.Failed(AiReasonCode("http_401")), provider(retries = 2).complete(request()))
        assertEquals(1, seen.size)
    }

    @Test
    fun `no server to talk to fails as a network or client error`() = runBlocking<Unit> {
        val closedPort = ServerSocket(0).use { it.localPort }
        val offline = AnthropicProvider.create(key, baseUrl = "http://127.0.0.1:$closedPort", maxRetries = 0, timeout = Duration.ofSeconds(2))
        val result = offline.complete(request())
        assertIs<AiCompletion.Failed>(result)
        assertTrue(result.code.value in setOf("network", "client"), result.code.value)
    }

    @Test
    fun `a reply that is not a valid message fails`() = runBlocking<Unit> {
        replies += Reply(200, "this is not json")
        assertIs<AiCompletion.Failed>(provider().complete(request()))
    }

    @Test
    fun `prices are known for the current Claude models only`() {
        val p = provider()
        assertEquals(AiPrice(100_000, 500_000, AiPrice(500_000, 2_500_000), 100_000), p.price("claude-haiku-5-5"))
        assertEquals(AiPrice(2_000_000, 10_000_000), p.price("claude-sonnet-5-5"))
        assertEquals(AiPrice(4_000_000, 20_000_000), p.price("claude-opus-5-5"))
        assertNull(p.price("claude-sonnet-4-20250514"), "a retired model has no price, so start-up refuses it")
    }

    @Test
    fun `an empty key is refused`() {
        assertFailsWith<IllegalArgumentException> { AnthropicProvider.create(" ") }
    }

    @Test
    fun `JSON schemas are passed to the SDK as plain values`() {
        val json = JsonObject(mapOf("s" to JsonPrimitive("x"), "b" to JsonPrimitive(false), "n" to JsonPrimitive(3), "d" to JsonPrimitive(0.5),
            "z" to JsonNull, "a" to JsonArray(listOf(JsonPrimitive(1)))))
        assertEquals(mapOf("s" to "x", "b" to false, "n" to 3L, "d" to 0.5, "z" to null, "a" to listOf(1L)), AnthropicProvider.plain(json))
    }

    // ── The categoriser on the port, end to end: same outcomes as AOS-512 ──

    private val calls = InMemoryAiCallLogRepository()
    private fun categoriser() = PortAiCategoryClient(AiGateway(
        listOf(AiRoute.of(AiFeature.CATEGORIZATION, provider(), "claude-haiku-5-5")),
        AiCostGuard(InMemoryAiSpendLedger(), 2_000_000), calls
    ))
    private val user = UUID(0, 702)

    @Test
    fun `a valid answer reaches the categoriser and is audited without content`() = runBlocking<Unit> {
        replies += Reply(200, message(good))
        val result = categoriser().ask(user, question)
        assertEquals(AiCategoryResult.Answered(AiCategoryAnswer(TransactionCategory.ENTERTAINMENT, 0.9, "PC game store"), "claude-haiku-5-5"), result)
        val call = calls.all.single()
        assertEquals(AiCallOutcome.ACCEPTED, call.outcome)
        assertEquals(usage, call.usage)
        assertEquals(22L, call.costMicros, "120 × 0.10 + 20 × 0.50 per million tokens, rounded up")
        assertFalse(call.toString().contains("Steam"))
    }

    @Test
    fun `refusals, cut-off and malformed answers are rejected and failures are unavailable, as in AOS-512`() = runBlocking<Unit> {
        replies += Reply(200, message("", stop = "refusal"))
        replies += Reply(200, message("{\"category\":", stop = "max_tokens"))
        replies += Reply(200, message("""{"category":"TRANSFER","confidence":0.99,"reason":"bank"}"""))
        replies += Reply(503, """{"type":"error","error":{"type":"api_error","message":"down"}}""")
        val c = categoriser()
        assertEquals(AiCategoryResult.Rejected("refused"), c.ask(user, question))
        assertEquals(AiCategoryResult.Rejected("truncated"), c.ask(user, question))
        assertEquals(AiCategoryResult.Rejected("category_not_allowed"), c.ask(user, question))
        assertEquals(AiCategoryResult.Unavailable("http_503"), c.ask(user, question))
        assertEquals(listOf(AiCallOutcome.REJECTED, AiCallOutcome.REJECTED, AiCallOutcome.REJECTED, AiCallOutcome.ERROR), calls.all.map { it.outcome })
    }
}
