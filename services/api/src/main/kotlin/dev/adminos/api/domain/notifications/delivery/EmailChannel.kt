package dev.adminos.api.domain.notifications.delivery

import dev.adminos.api.domain.notifications.Notification
import dev.adminos.api.domain.notifications.NotificationChannel
import dev.adminos.api.domain.notifications.NotificationPreferences

/** A plain-text email, validated so a bad value can't reach the mail server. */
data class EmailMessage(val to: String, val subject: String, val text: String) {
    init {
        require(EMAIL.matches(to)) { "Not an email address" }
        require(subject.isNotBlank() && subject.none { it == '\r' || it == '\n' }) { "A subject is one non-blank line" }
        require(text.isNotBlank()) { "An email needs a body" }
    }

    private companion object {
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}

/** Sends email. Implementations: SMTP (production) and fakes in tests. */
interface EmailSender {
    /** @return the provider's message id, if any. @throws EmailSendException on failure */
    suspend fun send(message: EmailMessage): String?
}

/** Why an email wasn't sent; [retryable] says whether trying again later can help. */
class EmailSendException(message: String, val retryable: Boolean, cause: Throwable? = null) : Exception(message, cause)

/** Email notifications (AOS-504): the title as the subject, the body, and links back to the app. */
class EmailChannel(private val sender: EmailSender, private val appUrl: String) : DeliveryChannel {
    override val channel = NotificationChannel.EMAIL

    override fun isWantedBy(prefs: NotificationPreferences) = prefs.emailNotifications

    override suspend fun deliver(notification: Notification, recipient: Recipient): DeliveryResult {
        val address = recipient.email?.trim()
        if (address.isNullOrEmpty()) return DeliveryResult.Permanent("The user has no email address")
        val message = try {
            compose(notification, address)
        } catch (e: IllegalArgumentException) {
            return DeliveryResult.Permanent("Can't build the email: ${e.message}")
        }
        return try {
            DeliveryResult.Delivered(sender.send(message))
        } catch (e: EmailSendException) {
            if (e.retryable) DeliveryResult.Retryable(e.message.orEmpty()) else DeliveryResult.Permanent(e.message.orEmpty())
        }
    }

    internal fun compose(notification: Notification, to: String) = EmailMessage(
        to = to,
        // One line: newlines in a subject would let text become mail headers.
        subject = notification.title.replace(Regex("[\\r\\n]+"), " ").trim(),
        text = buildString {
            appendLine(notification.body)
            appendLine()
            appendLine("Open AdminOS: $appUrl")
            appendLine()
            append("You get these emails because email notifications are on. Turn them off in Settings: $appUrl/settings")
        }
    )
}
