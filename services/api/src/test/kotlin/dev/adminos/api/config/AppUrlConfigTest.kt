package dev.adminos.api.config

import dev.adminos.api.domain.identity.isLocalDevUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-616: APP_URL is a bare origin, and plain http only on loopback, so cookies are always Secure off this machine. */
class AppUrlConfigTest {
    private fun env(value: String?): (String) -> String? = { key -> if (key == "APP_URL") value else null }

    @Test
    fun `unset or blank APP_URL means local development on localhost 3000`() {
        assertEquals("http://localhost:3000", AppConfig.appUrl(env(null)))
        assertEquals("http://localhost:3000", AppConfig.appUrl(env("  ")))
    }

    @Test
    fun `a tailnet https host is accepted, trimmed and without a trailing slash`() {
        assertEquals("https://adminos.tail1234.ts.net", AppConfig.appUrl(env(" https://adminos.tail1234.ts.net/ \n")))
        assertEquals("https://adminos.tail1234.ts.net:8443", AppConfig.appUrl(env("https://adminos.tail1234.ts.net:8443")))
    }

    @Test
    fun `plain http is accepted for loopback hosts only`() {
        for (url in listOf("http://localhost:3000", "http://127.0.0.1:3000", "http://[::1]:3000")) {
            assertEquals(url, AppConfig.appUrl(env(url)), url)
        }
    }

    @Test
    fun `plain http on any other host stops start-up`() {
        for (url in listOf("http://adminos.tail1234.ts.net", "http://100.101.102.103:3000", "http://192.168.1.5:3000",
                           "http://localhost.example.in", "http://0.0.0.0:3000")) {
            val e = assertFailsWith<IllegalArgumentException>(url) { AppConfig.appUrl(env(url)) }
            assertTrue(e.message!!.contains("must use https"), url)
        }
    }

    @Test
    fun `anything that is not a bare http or https origin stops start-up`() {
        for (url in listOf("adminos.tail1234.ts.net", "ftp://adminos.example", "https://", "https:///path",
                           "https://adminos.example/app", "https://adminos.example?x=1", "https://adminos.example#f",
                           "https://someone@adminos.example", "javascript:alert(1)", "https://admin os.example", "//adminos.example")) {
            val e = assertFailsWith<IllegalArgumentException>(url) { AppConfig.appUrl(env(url)) }
            assertTrue(e.message!!.contains("APP_URL must"), url)
        }
    }

    @Test
    fun `cookies keep Secure for the tailnet host and drop it only on loopback http`() {
        assertFalse(isLocalDevUrl("https://adminos.tail1234.ts.net"))
        assertFalse(isLocalDevUrl("https://localhost:3000"))
        assertTrue(isLocalDevUrl("http://localhost:3000"))
    }
}
