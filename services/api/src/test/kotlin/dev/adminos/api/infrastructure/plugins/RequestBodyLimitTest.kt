package dev.adminos.api.infrastructure.plugins

import dev.adminos.api.InfraIngestTestKit.config
import dev.adminos.api.startServer
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respondText
import io.ktor.server.request.receiveText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AUD-01 (AOS-615): bodies are capped before routing, authentication and decoding, so an unauthenticated
 * caller can't make the API buffer an arbitrarily large body.
 */
class RequestBodyLimitTest {

    private fun limited(block: suspend io.ktor.server.testing.ApplicationTestBuilder.(AtomicInteger) -> Unit) = testApplication {
        val reached = AtomicInteger(0)
        environment { this.config = MapApplicationConfig() }
        application {
            configureRequestId(); configureRequestBodyLimit(); configureSerialization(); configureErrorHandling()
            routing {
                post("/api/v1/echo") { reached.incrementAndGet(); call.respondText(call.receiveText().length.toString()) }
                post("/api/v1/documents") { reached.incrementAndGet(); call.respondText("ok") }
            }
        }
        block(reached)
    }

    @Test
    fun `a JSON body within the default limit reaches the route`() = limited { reached ->
        val res = client.post("/api/v1/echo") { contentType(ContentType.Application.Json); setBody("x".repeat(1000)) }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("1000", res.bodyAsText())
        assertEquals(1, reached.get())
    }

    @Test
    fun `a JSON body over the default limit is 413 and never reaches the route`() = limited { reached ->
        val res = client.post("/api/v1/echo") { contentType(ContentType.Application.Json); setBody("x".repeat((RequestBodyLimits.DEFAULT_LIMIT + 1).toInt())) }
        assertEquals(HttpStatusCode.PayloadTooLarge, res.status)
        assertTrue("REQUEST_TOO_LARGE" in res.bodyAsText())
        assertEquals(0, reached.get())
    }

    @Test
    fun `an upload route accepts more than the JSON limit but not more than the upload limit`() = limited { reached ->
        val ok = client.post("/api/v1/documents") { setBody(ByteArray((RequestBodyLimits.DEFAULT_LIMIT * 2).toInt())) }
        assertEquals(HttpStatusCode.OK, ok.status)
        val tooBig = client.post("/api/v1/documents") { setBody(ByteArray((RequestBodyLimits.UPLOAD_LIMIT + 1).toInt())) }
        assertEquals(HttpStatusCode.PayloadTooLarge, tooBig.status)
        assertTrue("FILE_TOO_LARGE" in tooBig.bodyAsText())
        assertEquals(1, reached.get())
    }

    @Test
    fun `the upload limit applies only to POST on the exact upload paths`() {
        assertEquals(RequestBodyLimits.UPLOAD_LIMIT, RequestBodyLimits.limitFor(HttpMethod.Post, "/api/v1/statements"))
        assertEquals(RequestBodyLimits.UPLOAD_LIMIT, RequestBodyLimits.limitFor(HttpMethod.Post, "/api/v1/documents/"))
        assertEquals(RequestBodyLimits.DEFAULT_LIMIT, RequestBodyLimits.limitFor(HttpMethod.Patch, "/api/v1/documents"))
        assertEquals(RequestBodyLimits.DEFAULT_LIMIT, RequestBodyLimits.limitFor(HttpMethod.Post, "/api/v1/documents/extra"))
        assertEquals(RequestBodyLimits.DEFAULT_LIMIT, RequestBodyLimits.limitFor(HttpMethod.Post, "/api/v1/statementsX"))
        assertEquals(RequestBodyLimits.DEFAULT_LIMIT, RequestBodyLimits.limitFor(HttpMethod.Post, "/api/v1/auth/google"))
    }

    /** Raw HTTP against the real Netty server, so the headers are exactly what an attacker would send. */
    private fun raw(port: Int, request: String): String = Socket("127.0.0.1", port).use { s ->
        s.soTimeout = 10_000
        s.getOutputStream().apply { write(request.toByteArray()); flush() }
        BufferedReader(InputStreamReader(s.getInputStream())).readLine().orEmpty()
    }

    @Test
    fun `the real server refuses an unauthenticated oversized sign-in body without reading it`() = runBlocking<Unit> {
        val server = startServer(config.copy(app = config.app.copy(port = 0, bindHost = "127.0.0.1")), wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            // Declares 100 MB but sends none of it: the answer must come straight away, from the headers alone.
            val status = raw(port, "POST /api/v1/auth/google HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: 104857600\r\n\r\n")
            assertEquals("HTTP/1.1 413 Payload Too Large", status)
        } finally {
            server.stop(100, 1000)
        }
    }

    @Test
    fun `the real server refuses a chunked body with no declared length`() = runBlocking<Unit> {
        val server = startServer(config.copy(app = config.app.copy(port = 0, bindHost = "127.0.0.1")), wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val status = raw(port, "POST /api/v1/auth/google HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n\r\n")
            assertEquals("HTTP/1.1 411 Length Required", status)
        } finally {
            server.stop(100, 1000)
        }
    }

    @Test
    fun `a chunked body with a small declared length is refused too, so the length can't be faked`() = runBlocking<Unit> {
        val server = startServer(config.copy(app = config.app.copy(port = 0, bindHost = "127.0.0.1")), wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            // Declares 2 bytes but streams chunks: without the rule, the server would read the chunked body and ignore the 2.
            val status = raw(port, "POST /api/v1/auth/google HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: 2\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n\r\n")
            assertTrue(status.startsWith("HTTP/1.1 4"), status) // 411 here, or 400 if the server rejects the conflicting headers first
        } finally {
            server.stop(100, 1000)
        }
    }
}
