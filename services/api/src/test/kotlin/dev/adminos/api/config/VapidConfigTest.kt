package dev.adminos.api.config

import dev.adminos.api.infrastructure.push.B64
import dev.adminos.api.infrastructure.push.P256
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VapidConfigTest {
    /** A key pair made per run: never a literal key in the repo. */
    private val pair = P256.generate()
    private val public = B64.encode(P256.encode(pair.public as ECPublicKey))
    private val private = B64.encode(P256.encode(pair.private as ECPrivateKey))
    private fun from(vararg env: Pair<String, String>) = VapidConfig.from { env.toMap()[it] }

    @Test
    fun `push is off without keys`() {
        assertNull(from())
        assertNull(from("VAPID_PUBLIC_KEY" to " ", "VAPID_PRIVATE_KEY" to ""))
    }

    @Test
    fun `a matching pair with a contact turns push on, and the private key is never printed`() {
        val c = from("VAPID_PUBLIC_KEY" to "$public=", "VAPID_PRIVATE_KEY" to private, "VAPID_SUBJECT" to "mailto:owner@example.com")!!
        assertEquals(public, c.publicKey)
        assertFalse(private in c.toString())
        assertTrue(public in c.toString())
    }

    @Test
    fun `half, mismatched or malformed settings stop start-up with a message naming the setting`() {
        val other = B64.encode(P256.encode(P256.generate().public as ECPublicKey))
        listOf(
            arrayOf("VAPID_PUBLIC_KEY" to public) to "VAPID_PRIVATE_KEY",
            arrayOf("VAPID_PRIVATE_KEY" to private) to "VAPID_PUBLIC_KEY",
            arrayOf("VAPID_PUBLIC_KEY" to public, "VAPID_PRIVATE_KEY" to private) to "VAPID_SUBJECT",
            arrayOf("VAPID_PUBLIC_KEY" to public, "VAPID_PRIVATE_KEY" to private, "VAPID_SUBJECT" to "owner@example.com") to "VAPID_SUBJECT",
            arrayOf("VAPID_PUBLIC_KEY" to other, "VAPID_PRIVATE_KEY" to private, "VAPID_SUBJECT" to "https://adminos.example") to "doesn't match",
            arrayOf("VAPID_PUBLIC_KEY" to public, "VAPID_PRIVATE_KEY" to "AAAA", "VAPID_SUBJECT" to "mailto:a@b.co") to "VAPID_PRIVATE_KEY",
        ).forEach { (env, expected) ->
            val e = assertFailsWith<IllegalArgumentException>(env.toList().toString()) { from(*env) }
            assertTrue(e.message.orEmpty().contains(expected), e.message)
        }
    }
}
