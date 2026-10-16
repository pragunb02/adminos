package dev.adminos.api.infrastructure.email

import com.icegreen.greenmail.configuration.GreenMailConfiguration
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetup
import com.icegreen.greenmail.util.ServerSetupTest
import dev.adminos.api.config.SmtpConfig
import dev.adminos.api.config.SmtpSecurity
import dev.adminos.api.domain.notifications.delivery.EmailMessage
import dev.adminos.api.domain.notifications.delivery.EmailSendException
import jakarta.mail.internet.InternetAddress
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.net.ServerSocket
import java.time.Duration
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The SMTP sender against a real (in-process) mail server, GreenMail (AOS-504). */
class SmtpEmailSenderTest {
    private val smtps = ServerSetupTest.SMTPS.dynamicPort()
    /** A throwaway login for the in-process test server, made up per run. */
    private val testLogin = java.util.UUID.randomUUID().toString()
    private val mail = GreenMail(smtps).withConfiguration(GreenMailConfiguration.aConfig().withUser("bot@adminos.test", "bot", testLogin))
    private val message = EmailMessage("pragun@example.com", "Bill due in 3 days", "HDFC bill of ₹4560.00 is due on 2026-10-19\n\nOpen AdminOS: https://adminos.example")

    /** The test server's certificate is self-signed for "localhost": trust it here only. */
    private val trustTestServer = Properties().apply {
        put("mail.smtps.ssl.trust", "*")
        put("mail.smtps.ssl.checkserveridentity", "false")
    }

    private fun config(port: Int = mail.smtps.port, password: String = testLogin, security: SmtpSecurity = SmtpSecurity.TLS) =
        SmtpConfig("127.0.0.1", port, security, "bot", password, InternetAddress("AdminOS <bot@adminos.test>"), Duration.ofSeconds(3))

    @BeforeEach fun start() = mail.start()
    @AfterEach fun stop() = mail.stop()

    @Test
    fun `sends a UTF-8 plain-text email over TLS with login`() = runBlocking<Unit> {
        val id = SmtpEmailSender(config(), trustTestServer).send(message)

        val received = mail.receivedMessages.single()
        assertEquals("Bill due in 3 days", received.subject)
        assertEquals("bot@adminos.test", (received.from.single() as InternetAddress).address)
        assertEquals("pragun@example.com", received.allRecipients.single().toString())
        assertTrue("₹4560.00" in GreenMailUtil.getBody(received).let { String(jakarta.mail.internet.MimeUtility.decode(it.byteInputStream(), "quoted-printable").readAllBytes(), Charsets.UTF_8) })
        assertEquals(received.messageID, id)
    }

    @Test
    fun `a wrong password is reported as retryable (fix the config and it will go)`() = runBlocking<Unit> {
        val e = assertFailsWith<EmailSendException> { SmtpEmailSender(config(password = testLogin.reversed()), trustTestServer).send(message) }
        assertTrue(e.retryable)
        assertEquals("The SMTP server rejected the username or password", e.message)
    }

    @Test
    fun `an unreachable server is retryable`() = runBlocking<Unit> {
        val closedPort = ServerSocket(0).use { it.localPort }
        val e = assertFailsWith<EmailSendException> { SmtpEmailSender(config(port = closedPort), trustTestServer).send(message) }
        assertTrue(e.retryable)
        assertTrue(e.message!!.startsWith("SMTP send failed"))
    }

    @Test
    fun `without a username it sends without logging in`() = runBlocking<Unit> {
        val plain = GreenMail(ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP)).also { it.start() }
        try {
            val noAuth = SmtpConfig("127.0.0.1", plain.smtp.port, SmtpSecurity.NONE, null, null, InternetAddress("bot@adminos.test"))
            SmtpEmailSender(noAuth).send(message)
            assertEquals(1, plain.receivedMessages.size)
        } finally {
            plain.stop()
        }
    }

    @Test
    fun `starttls is required, never a silent fall-back to plain text, and timeouts are always set`() {
        val p = SmtpEmailSender.properties(config(port = 587, security = SmtpSecurity.STARTTLS))
        assertEquals("true", p["mail.smtp.starttls.required"])
        assertEquals("smtp", p["mail.transport.protocol"])
        assertEquals("3000", p["mail.smtp.timeout"])
        assertEquals("3000", p["mail.smtp.connectiontimeout"])
        assertFalse(SmtpEmailSender.properties(config()).containsKey("mail.smtp.starttls.enable"))
    }
}
