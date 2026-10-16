package dev.adminos.api.infrastructure.email

import dev.adminos.api.config.SmtpConfig
import dev.adminos.api.config.SmtpSecurity
import dev.adminos.api.domain.notifications.delivery.EmailMessage
import dev.adminos.api.domain.notifications.delivery.EmailSendException
import dev.adminos.api.domain.notifications.delivery.EmailSender
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.SendFailedException
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Properties

/**
 * Sends email over SMTP with Jakarta Mail (AOS-504). Works with any provider (Gmail app password,
 * SES SMTP, Mailgun…). Timeouts are always set, so a stuck server can't hold a scheduler run.
 */
class SmtpEmailSender(
    private val config: SmtpConfig,
    /** Test seam only (e.g. trust a test server's self-signed certificate); production passes nothing. */
    testOverrides: Properties = Properties()
) : EmailSender {
    private val session: Session = Session.getInstance(properties(config).apply { putAll(testOverrides) })

    override suspend fun send(message: EmailMessage): String? = withContext(Dispatchers.IO) {
        val mime = MimeMessage(session).apply {
            setFrom(config.from)
            setRecipient(Message.RecipientType.TO, InternetAddress(message.to, true))
            setSubject(message.subject, Charsets.UTF_8.name())
            setText(message.text, Charsets.UTF_8.name())
            sentDate = Date()
        }
        try {
            mime.saveChanges()
            // An explicit transport for the configured protocol: the static Transport.send always uses plain
            // "smtp", which would ignore the TLS (smtps) settings.
            session.getTransport(protocol(config)).use { transport ->
                transport.connect(config.host, config.port, config.username, config.password)
                transport.sendMessage(mime, mime.allRecipients)
            }
            mime.messageID
        } catch (e: AuthenticationFailedException) {
            // Our configuration is wrong; once it is fixed, a retry will work.
            throw EmailSendException("The SMTP server rejected the username or password", retryable = true, e)
        } catch (e: SendFailedException) {
            // The server refused this recipient (e.g. no such mailbox): trying again won't help.
            throw EmailSendException("The SMTP server refused the recipient", retryable = false, e)
        } catch (e: MessagingException) {
            throw EmailSendException("SMTP send failed: ${e.message}", retryable = true, e)
        }
    }

    internal companion object {
        fun protocol(config: SmtpConfig) = if (config.security == SmtpSecurity.TLS) "smtps" else "smtp"

        fun properties(config: SmtpConfig) = Properties().apply {
            val protocol = protocol(config)
            put("mail.transport.protocol", protocol)
            put("mail.$protocol.host", config.host)
            put("mail.$protocol.port", config.port.toString())
            put("mail.$protocol.auth", (config.username != null).toString())
            put("mail.$protocol.connectiontimeout", config.timeout.toMillis().toString())
            put("mail.$protocol.timeout", config.timeout.toMillis().toString())
            put("mail.$protocol.writetimeout", config.timeout.toMillis().toString())
            when (config.security) {
                SmtpSecurity.STARTTLS -> {
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.starttls.required", "true") // never fall back to plain text
                    put("mail.smtp.ssl.checkserveridentity", "true")
                }
                SmtpSecurity.TLS -> put("mail.smtps.ssl.checkserveridentity", "true")
                SmtpSecurity.NONE -> Unit
            }
        }
    }
}
