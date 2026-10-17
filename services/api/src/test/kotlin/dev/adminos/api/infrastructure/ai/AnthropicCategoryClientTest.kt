package dev.adminos.api.infrastructure.ai

import com.sun.net.httpserver.HttpServer
import dev.adminos.api.config.AiCategorizationConfig
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.ai.AI_ALLOWED_CATEGORIES
import dev.adminos.api.domain.financial.ai.AiCategoryAnswer
import dev.adminos.api.domain.financial.ai.AiCategoryQuestion
import dev.adminos.api.domain.financial.ai.AiCategoryResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * AOS-512: the Claude adapter against a local fake of the Messages API (no real network, no real key).
 * Checks what is sent, how replies are read, and how failures are reported.
 */
class AnthropicCategoryClientTest {

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
    private val config = AiCategorizationConfig(key, "claude-haiku-5-5")
    private fun client(retries: Int = 0) =
        AnthropicCategoryClient.create(config, baseUrl = "http://127.0.0.1:${server.address.port}", maxRetries = retries, timeout = Duration.ofSeconds(5))

    private val question = AiCategoryQuestion("Steam", PaymentMethod.CARD_CREDIT, AI_ALLOWED_CATEGORIES)

    private fun message(text: String, stop: String = "end_turn") = """
        {"id":"msg_test","type":"message","role":"assistant","model":"claude-haiku-5-5",
         "content":[{"type":"text","text":${JsonPrimitive(text)}}],
         "stop_reason":"$stop","stop_sequence":null,"usage":{"input_tokens":120,"output_tokens":20}}
    """.trimIndent()

    private val good = """{"category":"ENTERTAINMENT","confidence":0.9,"reason":"PC game store"}"""

    @Test
    fun `a valid structured answer is accepted`() = runBlocking<Unit> {
        replies += Reply(200, message(good))
        val result = client().ask(question)
        assertEquals(AiCategoryResult.Answered(AiCategoryAnswer(TransactionCategory.ENTERTAINMENT, 0.9, "PC game store"), "claude-haiku-5-5"), result)
    }

    @Test
    fun `only the merchant name, payment method and allowed categories are sent`() = runBlocking<Unit> {
        replies += Reply(200, message(good))
        client().ask(question)
        val request = seen.single()
        assertEquals("/v1/messages", request.path)
        assertEquals(key, request.headers["x-api-key"])
        assertTrue(request.headers.containsKey("anthropic-version"))

        val body = Json.parseToJsonElement(request.body).jsonObject
        assertEquals("claude-haiku-5-5", body["model"]?.jsonPrimitive?.content)
        assertEquals("disabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("low", body["output_config"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        val format = body["output_config"]!!.jsonObject["format"]!!.jsonObject
        assertEquals("json_schema", format["type"]?.jsonPrimitive?.content)
        val enum = format["schema"]!!.jsonObject["properties"]!!.jsonObject["category"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(AI_ALLOWED_CATEGORIES.map { it.name }, enum)

        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        val content = messages[0].jsonObject["content"]!!
        val text = if (content is JsonPrimitive) content.content else content.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        val sent = Json.parseToJsonElement(text).jsonObject
        assertEquals(setOf("merchant", "paymentMethod", "allowedCategories"), sent.keys)
        assertEquals("Steam", sent["merchant"]?.jsonPrimitive?.content)
        assertEquals("CARD_CREDIT", sent["paymentMethod"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a missing payment method is sent as null`() {
        val sent = Json.parseToJsonElement(AnthropicCategoryClient.userMessage(question.copy(paymentMethod = null))).jsonObject
        assertEquals("null", sent["paymentMethod"].toString())
    }

    @Test
    fun `a refusal or a truncated reply is rejected`() = runBlocking<Unit> {
        replies += Reply(200, message("", stop = "refusal"))
        replies += Reply(200, message("{\"category\":", stop = "max_tokens"))
        assertEquals(AiCategoryResult.Rejected("refused"), client().ask(question))
        assertEquals(AiCategoryResult.Rejected("truncated"), client().ask(question))
    }

    @Test
    fun `a server error is retried with backoff, then answered`() = runBlocking<Unit> {
        replies += Reply(529, """{"type":"error","error":{"type":"overloaded_error","message":"busy"}}""", mapOf("retry-after-ms" to "10"))
        replies += Reply(200, message(good))
        assertIs<AiCategoryResult.Answered>(client(retries = 1).ask(question))
        assertEquals(2, seen.size)
    }

    @Test
    fun `a rate limit that outlasts the retries is unavailable with its status only`() = runBlocking<Unit> {
        repeat(2) { replies += Reply(429, """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}""", mapOf("retry-after-ms" to "10")) }
        assertEquals(AiCategoryResult.Unavailable("HTTP 429"), client(retries = 1).ask(question))
        assertEquals(2, seen.size)
    }

    @Test
    fun `a bad key is unavailable and not retried`() = runBlocking<Unit> {
        replies += Reply(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        assertEquals(AiCategoryResult.Unavailable("HTTP 401"), client(retries = 2).ask(question))
        assertEquals(1, seen.size)
    }

    @Test
    fun `no server to talk to is unavailable`() = runBlocking<Unit> {
        val closedPort = ServerSocket(0).use { it.localPort }
        val offline = AnthropicCategoryClient.create(config, baseUrl = "http://127.0.0.1:$closedPort", maxRetries = 0, timeout = Duration.ofSeconds(2))
        val result = offline.ask(question)
        assertIs<AiCategoryResult.Unavailable>(result)
        assertTrue(result.why.startsWith("network") || result.why.startsWith("client"), result.why)
    }

    @Test
    fun `a reply that is not a valid message is unavailable`() = runBlocking<Unit> {
        replies += Reply(200, "this is not json")
        assertIs<AiCategoryResult.Unavailable>(client().ask(question))
    }

    // ── Strict reading of the model's JSON ──

    private fun parse(text: String, allowed: List<TransactionCategory> = AI_ALLOWED_CATEGORIES) = AnthropicCategoryClient.parse(text, allowed, "m")

    @Test
    fun `anything but exactly category, confidence and reason is rejected`() {
        assertIs<AiCategoryResult.Answered>(parse("  $good \n"))
        val rejected = listOf(
            "not json", "[1,2]", "\"FOOD\"",
            """{"category":"FOOD","confidence":0.9}""",
            """{"category":"FOOD","confidence":0.9,"reason":"x","extra":1}""",
            """{"category":"RENT","confidence":0.9,"reason":"x"}""",
            """{"category":"food","confidence":0.9,"reason":"x"}""",
            """{"category":1,"confidence":0.9,"reason":"x"}""",
            """{"category":"FOOD","confidence":"0.9","reason":"x"}""",
            """{"category":"FOOD","confidence":1.5,"reason":"x"}""",
            """{"category":"FOOD","confidence":-0.1,"reason":"x"}""",
            """{"category":"FOOD","confidence":0.9,"reason":"  "}""",
            """{"category":"FOOD","confidence":0.9,"reason":3}""",
            """{"category":"FOOD","confidence":0.9,"reason":"${"x".repeat(201)}"}"""
        )
        for (r in rejected) assertIs<AiCategoryResult.Rejected>(parse(r), r)
    }

    @Test
    fun `a category outside the allowed list is rejected even if it is a real category`() {
        assertIs<AiCategoryResult.Rejected>(parse("""{"category":"TRANSFER","confidence":0.99,"reason":"bank"}"""))
        assertIs<AiCategoryResult.Rejected>(parse(good, allowed = listOf(TransactionCategory.FOOD, TransactionCategory.OTHER)))
    }

    @Test
    fun `the schema sent allows only the listed categories and no other fields`() = runBlocking<Unit> {
        replies += Reply(200, message(good))
        client().ask(question.copy(allowed = listOf(TransactionCategory.FOOD, TransactionCategory.OTHER)))
        val schema = Json.parseToJsonElement(seen.single().body).jsonObject["output_config"]!!.jsonObject["format"]!!.jsonObject["schema"]!!.jsonObject
        assertEquals("false", schema["additionalProperties"].toString())
        assertEquals(listOf("category", "confidence", "reason"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonObject(mapOf("type" to JsonPrimitive("string"), "enum" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("FOOD"), JsonPrimitive("OTHER"))))),
            schema["properties"]!!.jsonObject["category"])
    }
}
