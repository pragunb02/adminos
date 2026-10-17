package dev.adminos.api.infrastructure.google

import com.sun.net.httpserver.HttpServer
import dev.adminos.api.domain.ingestion.connection.GmailPushException
import dev.adminos.api.domain.ingestion.connection.runtimeToken
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** AOS-405: users.watch / users.stop against a local stand-in for Gmail (no real network). */
class HttpGmailPushApiTest {

    private class Seen(val method: String, val path: String, val authorization: String?, val body: String)

    @Volatile private var status = 200
    @Volatile private var body = """{"historyId":"1234567","expiration":"1761285600000"}"""
    private val seen = CopyOnWriteArrayList<Seen>()

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
        createContext("/") { ex ->
            seen += Seen(ex.requestMethod, ex.requestURI.path, ex.requestHeaders.getFirst("Authorization"), ex.requestBody.readAllBytes().decodeToString())
            val bytes = body.toByteArray()
            if (status == 204) ex.sendResponseHeaders(204, -1) else {
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(status, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            ex.close()
        }
        start()
    }
    private val api = HttpGmailPushApi("http://127.0.0.1:${server.address.port}")
    private val topic = "projects/adminos-dev/topics/gmail-push"

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun `watch posts the topic and inbox filter with the bearer token`() = runBlocking<Unit> {
        val token = runtimeToken()

        api.watch(token, topic)

        val request = seen.single()
        assertEquals("POST", request.method)
        assertEquals("/gmail/v1/users/me/watch", request.path)
        assertEquals("Bearer $token", request.authorization)
        val json = Json.parseToJsonElement(request.body).jsonObject
        assertEquals(topic, json["topicName"]?.jsonPrimitive?.content)
        assertEquals(listOf("INBOX"), json["labelIds"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals("include", json["labelFilterBehavior"]?.jsonPrimitive?.content)
    }

    @Test
    fun `watch reads gmail's history id and its expiry in epoch milliseconds`() = runBlocking<Unit> {
        val registration = api.watch(runtimeToken(), topic)

        assertEquals("1234567", registration.historyId)
        assertEquals(Instant.ofEpochMilli(1761285600000), registration.expiresAt)
    }

    @Test
    fun `a 401 is unauthorized and other statuses are rejections without google's body`() = runBlocking<Unit> {
        status = 401
        assertFailsWith<GmailPushException.Unauthorized> { api.watch(runtimeToken(), topic) }

        status = 403
        body = """{"error":{"message":"User not authorized to perform this action. topic secret-project"}}"""
        val e = assertFailsWith<GmailPushException.Rejected> { api.watch(runtimeToken(), topic) }
        assertEquals(403, e.status)
        assertEquals("Gmail answered HTTP 403", e.message)
    }

    @Test
    fun `an unreadable answer counts as gmail being unavailable`() = runBlocking<Unit> {
        body = """{"historyId":"1"}"""
        assertFailsWith<GmailPushException.Unavailable> { api.watch(runtimeToken(), topic) }

        body = """{"historyId":"1","expiration":"soon"}"""
        assertFailsWith<GmailPushException.Unavailable> { api.watch(runtimeToken(), topic) }

        body = """{"historyId":"h-1","expiration":"1761285600000"}"""
        assertFailsWith<GmailPushException.Unavailable> { api.watch(runtimeToken(), topic) }
    }

    @Test
    fun `an unreachable gmail is unavailable`() = runBlocking<Unit> {
        server.stop(0)

        assertFailsWith<GmailPushException.Unavailable> { api.watch(runtimeToken(), topic) }
        assertFailsWith<GmailPushException.Unavailable> { api.stop(runtimeToken()) }
    }

    @Test
    fun `stop posts to users stop and accepts 204`() = runBlocking<Unit> {
        status = 204
        val token = runtimeToken()

        api.stop(token)

        val request = seen.single()
        assertEquals("/gmail/v1/users/me/stop", request.path)
        assertEquals("Bearer $token", request.authorization)
    }

    @Test
    fun `a failed stop is reported by status`() = runBlocking<Unit> {
        status = 404
        assertEquals(404, assertFailsWith<GmailPushException.Rejected> { api.stop(runtimeToken()) }.status)

        status = 401
        assertFailsWith<GmailPushException.Unauthorized> { api.stop(runtimeToken()) }
    }
}
