package dev.adminos.api.domain.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-610: the pure rules behind login hardening. */
class IdentityLoginHardeningTest {

    @Test
    fun `only plain-http loopback hosts count as local development`() {
        for (url in listOf("http://localhost:3000", "http://LOCALHOST", "http://127.0.0.1:3000/x", "http://[::1]:3000")) assertTrue(isLocalDevUrl(url), url)
        for (url in listOf("http://localhost.example.in", "https://localhost:3000", "http://127.0.0.1.nip.io", "https://adminos.example.in",
                           "http://10.0.0.5:3000", "not a url ::", "localhost")) assertFalse(isLocalDevUrl(url), url)
    }

    @Test
    fun `form fields are URL-encoded so a code can't inject fields`() {
        assertEquals("code=4%2F0Ab%2Bc%252F%26grant_type%3Devil&redirect_uri=https%3A%2F%2Fa.in%2Fcb%3Fx%3D1",
            formEncode("code" to "4/0Ab+c%2F&grant_type=evil", "redirect_uri" to "https://a.in/cb?x=1"))
    }

    @Test
    fun `only active, undeleted users can sign in`() {
        val u = User(email = "a@b.in", googleId = "g", name = "A")
        assertTrue(u.canSignIn())
        assertFalse(u.copy(isActive = false).canSignIn())
        assertFalse(u.copy(deletedAt = java.time.Instant.now()).canSignIn())
    }
}
