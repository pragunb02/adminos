package dev.adminos.api.infrastructure.queue

import io.lettuce.core.RedisURI
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Redis connection must carry the password, and no message shows the URL or the password (AOS-634). */
class RedisUrlTest {
    /** Built at run time, so no credential-shaped literal sits in the repo. */
    private val password = UUID.randomUUID().toString().replace("-", "")
    private fun withUserInfo(userInfo: String, host: String = "redis:6379") = "redis://" + userInfo + "@" + host
    private fun passwordOf(uri: RedisURI) =
        String(uri.credentialsProvider.resolveCredentialsAsync().toCompletableFuture().get().password)

    @Test
    fun `a bare URL with REDIS_PASSWORD beside it (the compose shape) gets that password`() {
        val uri = RedisUrl.parse("redis://redis:6379", password)
        assertEquals("redis", uri.host)
        assertEquals(6379, uri.port)
        assertEquals(password, passwordOf(uri))
    }

    @Test
    fun `a URL carrying the password works without REDIS_PASSWORD`() {
        assertEquals(password, passwordOf(RedisUrl.parse(withUserInfo(":$password"), null)))
    }

    @Test
    fun `the same password in both places is accepted`() {
        assertEquals(password, passwordOf(RedisUrl.parse(withUserInfo(":$password"), password)))
    }

    @Test
    fun `two different passwords are refused without showing either`() {
        val other = UUID.randomUUID().toString()
        val e = assertFailsWith<IllegalStateException> { RedisUrl.parse(withUserInfo(":$password"), other) }
        assertTrue("disagree" in e.message!!)
        assertFalse(password in e.message!! || other in e.message!!)
    }

    @Test
    fun `the parsed URI's text never shows the password`() {
        assertFalse(password in RedisUrl.parse("redis://redis:6379", password).toString())
        assertFalse(password in RedisUrl.parse(withUserInfo(":$password"), null).toString())
    }

    @Test
    fun `no password anywhere is refused without echoing the URL`() {
        val e = assertFailsWith<IllegalStateException> { RedisUrl.parse("redis://redis:6379", null) }
        assertTrue("requires a password" in e.message!!, e.message)
        assertFalse("redis:6379" in e.message!!, "the URL is never echoed")
    }

    @Test
    fun `a blank REDIS_PASSWORD and an empty URL password count as none`() {
        assertFailsWith<IllegalStateException> { RedisUrl.parse(withUserInfo(":"), "   ") }
    }

    @Test
    fun `a malformed URL is refused without quoting it`() {
        val bad = "redis://:" + password + "@bad host:not-a-port"
        val e = assertFailsWith<IllegalStateException> { RedisUrl.parse(bad, null) }
        assertFalse(password in e.message!!, e.message)
        assertEquals(null, e.cause, "the parser's exception, which may quote the URL, is not chained")
    }

    @Test
    fun `a URL that isn't Redis at all is refused`() {
        val e = assertFailsWith<IllegalStateException> { RedisUrl.parse("http://" + password + "@example.com", password) }
        assertFalse(password in e.message!!)
    }
}
