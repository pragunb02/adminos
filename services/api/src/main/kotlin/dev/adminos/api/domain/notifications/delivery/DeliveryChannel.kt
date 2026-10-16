package dev.adminos.api.domain.notifications.delivery

import dev.adminos.api.domain.notifications.Notification
import dev.adminos.api.domain.notifications.NotificationChannel
import dev.adminos.api.domain.notifications.NotificationPreferences
import java.util.UUID

/**
 * One way of reaching the user — in-app, email, Web Push… (AOS-504). A new channel is one class
 * registered in Koin as a `DeliveryChannel`; the dispatcher and the notification service don't change.
 * A channel that isn't configured (e.g. no SMTP server) is simply not registered, so nothing is
 * ever recorded as "sent" through it.
 */
interface DeliveryChannel {
    val channel: NotificationChannel

    /** Whether this user has this channel switched on in their preferences. */
    fun isWantedBy(prefs: NotificationPreferences): Boolean

    /**
     * Sends [notification] to [recipient]. Expected failures are returned as a [DeliveryResult];
     * an exception is treated like [DeliveryResult.Retryable] by the dispatcher.
     */
    suspend fun deliver(notification: Notification, recipient: Recipient): DeliveryResult
}

/** Who a notification goes to: only what channels need, looked up once per delivery. */
data class Recipient(val userId: UUID, val email: String?)

sealed interface DeliveryResult {
    /** Handed to the provider. [reference] is the provider's message id, if it gives one. */
    data class Delivered(val reference: String? = null) : DeliveryResult

    /** Might work later (provider down, timeout, rate limit): retried with backoff. */
    data class Retryable(val reason: String) : DeliveryResult

    /** Will never work as things are (no email address, address rejected): marked failed now. */
    data class Permanent(val reason: String) : DeliveryResult
}
