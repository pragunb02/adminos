package dev.adminos.api.infrastructure.ai

import com.sun.net.httpserver.HttpServer
import dev.adminos.api.domain.ai.AiCompletion
import dev.adminos.api.domain.ai.AiPrice
import dev.adminos.api.domain.ai.AiProviderKind
import dev.adminos.api.domain.ai.AiReasonCode
import dev.adminos.api.domain.ai.AiRequest
import dev.adminos.api.domain.ai.AiUsage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** AOS-702: the Ollama adapter against a local stub of `POST /api/chat` (no Ollama needed). */
class OllamaProviderTest {

    private data class Reply(val status: Int, val body: String, val delay: Duration = Duration.ZERO)
    private data class Seen(val path: String, val body: String)

    private val seen = CopyOnWriteArrayList<Seen>()
    private val replies = ArrayDeque<Reply>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            seen += Seen(ex.requestURI.path, ex.requestBody.readBytes().decodeToString())
            val reply = synchronized(replies) { replies.removeFirstOrNull() } ?: Reply(500, """{"error":"no reply"}""")
            Thread.sleep(reply.delay.toMillis())
            val bytes = reply.body.toByteArray()
            ex.responseHeaders.add("content-type", "application/json")
            ex.sendResponseHeaders(reply.status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun provider(timeout: Duration = Duration.ofSeconds(5), base: String = "http://127.0.0.1:${server.address.port}") = OllamaProvider(URI(base), timeout)

    private val schema = JsonObject(mapOf("type" to JsonPrimitive("object")))
    private val request = AiRequest("qwen3:4b", "You categorize merchants.", """{"merchant":"Steam"}""", schema, 300)

    private fun chat(content: String, done: String = "stop") =
        """{"model":"qwen3:4b","message":{"role":"assistant","content":${JsonPrimitive(content)}},"done":true,"done_reason":"$done","prompt_eval_count":85,"eval_count":19}"""

    @Test
    fun `a reply is returned as text with its token counts`() = runBlocking<Unit> {
        replies += Reply(200, chat("""{"category":"ENTERTAINMENT"}"""))
        assertEquals(AiCompletion.Completed("""{"category":"ENTERTAINMENT"}""", AiUsage(85, 19)), provider().complete(request))
    }

    @Test
    fun `the request asks for the schema, no streaming, no thinking and temperature 0`() = runBlocking<Unit> {
        replies += Reply(200, chat("{}"))
        provider(base = "http://127.0.0.1:${server.address.port}/").complete(request)
        val sent = seen.single()
        assertEquals("/api/chat", sent.path)
        val body = Json.parseToJsonElement(sent.body).jsonObject
        assertEquals("qwen3:4b", body["model"]?.jsonPrimitive?.content)
        assertEquals(schema, body["format"])
        assertEquals("false", body["stream"].toString())
        assertEquals("false", body["think"].toString())
        assertEquals("0", body.getValue("options").jsonObject["temperature"].toString())
        assertEquals("300", body.getValue("options").jsonObject["num_predict"].toString())
        val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user"), messages.map { it["role"]?.jsonPrimitive?.content })
        assertEquals("""{"merchant":"Steam"}""", messages[1]["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an answer cut off at the output limit is truncated`() = runBlocking<Unit> {
        replies += Reply(200, chat("{\"category\":", done = "length"))
        assertEquals(AiCompletion.Truncated(AiUsage(85, 19)), provider().complete(request))
    }

    @Test
    fun `a model that isn't pulled fails with its status`() = runBlocking<Unit> {
        replies += Reply(404, """{"error":"model 'qwen3:4b' not found"}""")
        assertEquals(AiCompletion.Failed(AiReasonCode("http_404")), provider().complete(request))
    }

    @Test
    fun `a reply that isn't a chat message is a bad reply`() = runBlocking<Unit> {
        for (body in listOf("not json", "[]", """{"message":{}}""", """{"message":{"content":3}}""")) {
            replies += Reply(200, body)
            assertEquals(AiCompletion.Failed(AiReasonCode("bad_reply")), provider().complete(request), body)
        }
    }

    @Test
    fun `missing token counts are zero`() = runBlocking<Unit> {
        replies += Reply(200, """{"message":{"content":"{}"},"done_reason":7}""")
        assertEquals(AiCompletion.Completed("{}", AiUsage.NONE), provider().complete(request))
    }

    @Test
    fun `a slow model times out and a stopped server is a network failure`() = runBlocking<Unit> {
        replies += Reply(200, chat("{}"), delay = Duration.ofMillis(800))
        assertEquals(AiCompletion.Failed(AiReasonCode("timeout")), provider(timeout = Duration.ofMillis(200)).complete(request))
        val closed = ServerSocket(0).use { it.localPort }
        assertEquals(AiCompletion.Failed(AiReasonCode("network")), provider(base = "http://127.0.0.1:$closed").complete(request))
    }

    @Test
    fun `local models are free and the settings are validated`() {
        assertEquals(AiPrice.FREE, provider().price("anything:1b"))
        assertEquals(AiProviderKind.OLLAMA, provider().kind)
        assertFailsWith<IllegalArgumentException> { OllamaProvider(URI("ftp://ollama"), Duration.ofSeconds(1)) }
        assertFailsWith<IllegalArgumentException> { OllamaProvider(URI("http:///nohost"), Duration.ofSeconds(1)) }
        assertFailsWith<IllegalArgumentException> { OllamaProvider(URI("http://ollama"), Duration.ZERO) }
    }
}
