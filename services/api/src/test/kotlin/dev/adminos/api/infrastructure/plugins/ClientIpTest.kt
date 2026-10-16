package dev.adminos.api.infrastructure.plugins

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.config.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** D-32: behind the web proxy, the client IP comes from the last X-Forwarded-For hop only. */
class ClientIpTest {

    private fun ipTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureForwardedHeaders()
            routing { get("/ip") { call.respondText(call.clientIp) } }
        }
        block()
    }

    @Test
    fun `uses the hop added by our proxy`() = ipTest {
        assertEquals("203.0.113.9", client.get("/ip") { header("X-Forwarded-For", "203.0.113.9") }.bodyAsText())
    }

    @Test
    fun `a client-supplied X-Forwarded-For cannot spoof the ip`() = ipTest {
        // client sent "6.6.6.6"; our proxy appended the real peer 203.0.113.9
        val ip = client.get("/ip") { header("X-Forwarded-For", "6.6.6.6, 203.0.113.9") }.bodyAsText()
        assertEquals("203.0.113.9", ip)
        assertNotEquals("6.6.6.6", ip)
    }

    @Test
    fun `without the header the tcp peer is used`() = ipTest {
        val ip = client.get("/ip").bodyAsText()
        assertTrue(ip.isNotBlank() && ip != "6.6.6.6", "falls back to the connection peer, got '$ip'")
    }
}
