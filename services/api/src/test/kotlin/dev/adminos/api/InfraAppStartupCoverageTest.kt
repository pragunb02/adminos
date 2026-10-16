package dev.adminos.api

import dev.adminos.api.InfraIngestTestKit.config
import dev.adminos.api.InfraIngestTestKit.tokenFor
import dev.adminos.api.InfraIngestTestKit.user
import dev.adminos.api.infrastructure.plugins.ACCESS_TOKEN_COOKIE
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The real `Application.module` (in-memory configuration) and the Netty start-up used by `main` (AOS-312). */
class InfraAppStartupCoverageTest {

    private fun appTest(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { this.config = MapApplicationConfig() }
        application { module(config) }
        block()
    }

    @Test
    fun `health route answers ok with the standard envelope`() = appTest {
        val res = client.get("/health")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals("ok", body["data"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertTrue(body["meta"]!!.jsonObject["requestId"]!!.jsonPrimitive.content.startsWith("req_"))
    }

    @Test
    fun `protected routes reject a request without credentials`() = appTest {
        val res = client.post("/api/v1/ingest/events") { contentType(ContentType.Application.Json); setBody("""{"events":[]}""") }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertTrue("AUTH_002" in res.bodyAsText())
    }

    @Test
    fun `a signed-in user can use the ingestion routes wired by the module`() = appTest {
        val res = client.post("/api/v1/transactions/cash") {
            cookie(ACCESS_TOKEN_COOKIE, tokenFor(user()))
            contentType(ContentType.Application.Json)
            setBody("""{"clientRef":"cash-0001-abcd","amount":"120","category":"food","note":"Chai"}""")
        }
        assertEquals(HttpStatusCode.Accepted, res.status, res.bodyAsText())
    }

    @Test
    fun `malformed JSON is a 400, not a 500`() = appTest {
        val res = client.post("/api/v1/transactions/cash") {
            cookie(ACCESS_TOKEN_COOKIE, tokenFor(user()))
            contentType(ContentType.Application.Json)
            setBody("""{"clientRef": 12""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue("VALIDATION_004" in res.bodyAsText())
    }

    @Test
    fun `CORS preflight from the web app origin is allowed with credentials`() = appTest {
        val res = client.options("/api/v1/transactions/cash") {
            header(HttpHeaders.Origin, config.app.appUrl)
            header(HttpHeaders.AccessControlRequestMethod, "POST")
        }
        assertEquals(config.app.appUrl, res.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals("true", res.headers[HttpHeaders.AccessControlAllowCredentials])
    }

    @Test
    fun `CORS rejects an unknown origin`() = appTest {
        val res = client.options("/api/v1/transactions/cash") {
            header(HttpHeaders.Origin, "https://evil.example.com")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
        }
        assertEquals(null, res.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `startServer serves the app on the configured loopback port and stops cleanly`() = runBlocking<Unit> {
        val server = startServer(config.copy(app = config.app.copy(port = 0, bindHost = "127.0.0.1")), wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            assertEquals(200, res.statusCode())
            assertTrue("\"ok\"" in res.body())
        } finally {
            server.stop(100, 1_000)
        }
    }
}
