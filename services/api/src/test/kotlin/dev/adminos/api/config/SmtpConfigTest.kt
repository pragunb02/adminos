package dev.adminos.api.config

import jakarta.mail.internet.InternetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmtpConfigTest {
    private fun from(vararg env: Pair<String, String>) = SmtpConfig.from { env.toMap()[it] }
    /** Made up per run: never a literal credential in the repo. */
    private val login = java.util.UUID.randomUUID().toString()
    private val base = arrayOf("SMTP_HOST" to "smtp.gmail.com", "SMTP_FROM" to "AdminOS <me@example.com>")

    @Test
    fun `email is off when no SMTP host is set`() {
        assertNull(from())
        assertNull(from("SMTP_HOST" to "  "))
    }

    @Test
    fun `defaults are STARTTLS on 587, or 465 for TLS`() {
        val c = from(*base, "SMTP_USERNAME" to "me@example.com", "SMTP_PASSWORD" to login)!!
        assertEquals(SmtpSecurity.STARTTLS, c.security)
        assertEquals(587, c.port)
        assertEquals("me@example.com", c.from.address)
        assertEquals(465, from(*base, "SMTP_SECURITY" to "TLS")!!.port)
        assertEquals(2525, from(*base, "SMTP_PORT" to "2525")!!.port)
    }

    @Test
    fun `the password never appears in logs`() {
        val c = from(*base, "SMTP_USERNAME" to "me@example.com", "SMTP_PASSWORD" to login)!!
        assertFalse(login in c.toString())
        assertTrue("smtp.gmail.com" in c.toString())
        assertTrue("username=-" in from(*base)!!.toString())
    }

    @Test
    fun `half or unsafe settings stop start-up with a message naming the setting`() {
        listOf(
            arrayOf("SMTP_HOST" to "smtp.gmail.com") to "SMTP_FROM",
            arrayOf(*base, "SMTP_FROM" to "not an address") to "SMTP_FROM",
            arrayOf(*base, "SMTP_PORT" to "abc") to "SMTP_PORT",
            arrayOf(*base, "SMTP_PORT" to "70000") to "SMTP_PORT",
            arrayOf(*base, "SMTP_SECURITY" to "ssl3") to "SMTP_SECURITY",
            arrayOf(*base, "SMTP_USERNAME" to "me") to "SMTP_PASSWORD",
            arrayOf(*base, "SMTP_SECURITY" to "none", "SMTP_USERNAME" to "me", "SMTP_PASSWORD" to login) to "SMTP_SECURITY",
        ).forEach { (env, key) ->
            val e = assertFailsWith<IllegalArgumentException>(env.toList().toString()) { from(*env) }
            assertTrue(e.message.orEmpty().contains(key), e.message)
        }
        assertFailsWith<IllegalArgumentException> { SmtpConfig(" ", 25, SmtpSecurity.NONE, null, null, InternetAddress("a@b.co")) }
    }
}
