package dev.adminos.api.infrastructure.prices

import com.sun.net.httpserver.HttpServer
import dev.adminos.api.domain.wealth.prices.PriceFetchException
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** AOS-810: one plain GET of a public file; https only, no redirects, size-capped, nothing identifying sent. */
class HttpAmfiNavSourceTest {

    private fun server(status: Int, body: String, headers: Map<String, String> = emptyMap(), seen: (Map<String, List<String>>) -> Unit = {}): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/spages/NAVAll.txt") { ex ->
                seen(ex.requestHeaders)
                headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                ex.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
            }
            start()
        }

    private fun HttpServer.url() = "http://127.0.0.1:${address.port}/spages/NAVAll.txt"

    @Test
    fun `the file is downloaded with no cookies or credentials`() = runBlocking<Unit> {
        var headers: Map<String, List<String>> = emptyMap()
        val s = server(200, "Scheme Code;x", seen = { headers = it })
        try {
            assertEquals("Scheme Code;x", HttpAmfiNavSource(s.url()).download())
            assertTrue(headers.keys.none { it.equals("Cookie", true) || it.equals("Authorization", true) })
        } finally { s.stop(0) }
    }

    @Test
    fun `an error status, a redirect or an oversized file is a failure, never a partial file`() = runBlocking<Unit> {
        val down = server(503, "busy")
        try { assertFailsWith<PriceFetchException> { HttpAmfiNavSource(down.url()).download() } } finally { down.stop(0) }
        val moved = server(302, "", mapOf("Location" to "http://127.0.0.1:9/elsewhere"))
        try { assertFailsWith<PriceFetchException> { HttpAmfiNavSource(moved.url()).download() } } finally { moved.stop(0) }
        val big = server(200, "x".repeat(2048))
        try { assertFailsWith<PriceFetchException> { HttpAmfiNavSource(big.url(), maxBytes = 1024).download() } } finally { big.stop(0) }
        assertFailsWith<PriceFetchException> { HttpAmfiNavSource("http://127.0.0.1:1/spages/NAVAll.txt").download() }
    }

    @Test
    fun `only https is accepted for a real host`() {
        assertFailsWith<IllegalArgumentException> { HttpAmfiNavSource("http://portal.amfiindia.com/spages/NAVAll.txt") }
        assertFailsWith<IllegalArgumentException> { HttpAmfiNavSource("file:///etc/passwd") }
        HttpAmfiNavSource() // the default is https
    }
}
