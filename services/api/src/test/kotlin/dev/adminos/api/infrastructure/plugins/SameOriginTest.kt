package dev.adminos.api.infrastructure.plugins

import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-704, D-100: cookie-authenticated mutations must come from the app itself; everything else is refused. */
class SameOriginTest {

    private val app = "http://localhost:3000"

    private fun allows(method: HttpMethod = HttpMethod.Patch, vararg headers: Pair<String, String>) =
        SameOrigin.allows(method, { name -> headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second }, app)

    @Test
    fun `reads always pass`() {
        for (m in listOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)) assertTrue(allows(m, "Sec-Fetch-Site" to "cross-site"), m.value)
    }

    @Test
    fun `the browser's same-origin signal passes`() {
        assertTrue(allows(headers = arrayOf("Sec-Fetch-Site" to "same-origin")))
        assertTrue(allows(headers = arrayOf("Sec-Fetch-Site" to " Same-Origin ")))
        assertTrue(allows(headers = arrayOf("Sec-Fetch-Site" to "none")), "typed or bookmarked by the user")
    }

    @Test
    fun `cross-site and same-site requests are refused, whatever Origin claims`() {
        assertFalse(allows(headers = arrayOf("Sec-Fetch-Site" to "cross-site")))
        assertFalse(allows(headers = arrayOf("Sec-Fetch-Site" to "same-site")), "a sibling subdomain is not the app")
        assertFalse(allows(headers = arrayOf("Sec-Fetch-Site" to "cross-site", "Origin" to app)))
        assertFalse(allows(headers = arrayOf("Sec-Fetch-Site" to "")))
    }

    @Test
    fun `without the browser signal, Origin must be the app's own`() {
        assertTrue(allows(headers = arrayOf("Origin" to "http://localhost:3000")))
        assertTrue(allows(headers = arrayOf("Origin" to "HTTP://LOCALHOST:3000")))
        for (bad in listOf("https://evil.example", "http://localhost:3001", "http://localhost:3000.evil.example", "null", "", "javascript:alert(1)", "http://local host:3000")) {
            assertFalse(allows(headers = arrayOf("Origin" to bad)), bad)
        }
    }

    @Test
    fun `a cookie mutation with no signal at all is refused (fails closed)`() {
        assertFalse(allows())
        assertFalse(allows(HttpMethod.Post))
        assertFalse(allows(HttpMethod.Delete))
    }

    @Test
    fun `a request with an Authorization header is not a cross-site forgery and passes`() {
        assertTrue(allows(headers = arrayOf("Authorization" to "Bearer abc")))
        assertFalse(allows(headers = arrayOf("Authorization" to " ")), "an empty header doesn't count")
    }

    @Test
    fun `origins are normalised without default ports, and junk isn't an origin`() {
        assertEquals("https://adminos.example", SameOrigin.originOf("https://AdminOS.example:443/settings?x=1"))
        assertEquals("http://localhost", SameOrigin.originOf("http://localhost:80"))
        assertEquals("http://127.0.0.1:3000", SameOrigin.originOf(" http://127.0.0.1:3000/ "))
        for (junk in listOf("ftp://x", "localhost:3000", "/relative", "http://", "%%%")) assertNull(SameOrigin.originOf(junk), junk)
    }
}
