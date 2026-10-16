package dev.adminos.api.domain.notifications.delivery

import dev.adminos.api.domain.identity.UserRepository
import dev.adminos.api.domain.notifications.Notification
import dev.adminos.api.domain.notifications.NotificationChannel
import dev.adminos.api.domain.notifications.NotificationPreferences
import dev.adminos.api.domain.notifications.NotificationRepository
import dev.adminos.api.domain.notifications.NotificationStatus
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/**
 * Delivers stored notifications through their channel and records the outcome (AOS-504):
 *
 * | Result      | Record becomes                                                        |
 * |-------------|-----------------------------------------------------------------------|
 * | Delivered   | SENT, with the provider reference                                     |
 * | Retryable   | PENDING again after a backoff (5 min, 30 min, 3 h); FAILED after [MAX_ATTEMPTS] |
 * | Permanent   | FAILED, with the reason                                               |
 *
 * Delivery is at-least-once: if the process dies between sending and recording, the claim lease
 * expires and the notification is sent again. That beats losing a bill reminder.
 */
class NotificationDispatcher(
    channels: List<DeliveryChannel>,
    private val users: UserRepository,
    private val notifications: NotificationRepository,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = LoggerFactory.getLogger(NotificationDispatcher::class.java)
    private val byChannel: Map<NotificationChannel, DeliveryChannel> = channels.associateBy { it.channel }

    init {
        require(byChannel.size == channels.size) { "Two delivery channels share a type: ${channels.map { it.channel }}" }
        require(NotificationChannel.IN_APP in byChannel) { "The in-app channel is required" }
        val off = NotificationChannel.entries - byChannel.keys
        logger.info("Notification channels on: {}{}", byChannel.keys.sorted(), if (off.isEmpty()) "" else "; not configured: ${off.sorted()}")
    }

    /** The configured channels this user wants, in-app first. */
    fun channelsFor(prefs: NotificationPreferences): List<NotificationChannel> =
        byChannel.values.filter { it.isWantedBy(prefs) }.map { it.channel }.sortedBy { it != NotificationChannel.IN_APP }

    /** Delivers [notification] (already stored and claimed) and returns the updated record. */
    suspend fun deliver(notification: Notification): Notification {
        val result = attempt(notification)
        val now = clock.instant()
        val updated = when (result) {
            is DeliveryResult.Delivered -> notification.copy(
                status = NotificationStatus.SENT, sentAt = now, fcmMessageId = result.reference,
                attempts = notification.attempts + 1, lastError = null, deliverAfter = null
            )
            is DeliveryResult.Permanent -> failed(notification, result.reason)
            is DeliveryResult.Retryable -> {
                val attempts = notification.attempts + 1
                if (attempts >= MAX_ATTEMPTS) failed(notification, "Gave up after $attempts attempts: ${result.reason}")
                else notification.copy(status = NotificationStatus.PENDING, attempts = attempts, lastError = result.reason,
                    deliverAfter = now.plus(backoff(attempts)))
            }
        }
        if (result !is DeliveryResult.Delivered) {
            logger.warn("Notification {} via {} not delivered ({}): {}", notification.id, notification.channel, updated.status, updated.lastError)
        }
        return notifications.update(updated)
    }

    private suspend fun attempt(notification: Notification): DeliveryResult {
        val channel = byChannel[notification.channel]
            ?: return DeliveryResult.Permanent("The ${notification.channel.name.lowercase()} channel is not configured")
        return try {
            // No user record means no address: channels that need one (email) fail permanently; in-app doesn't care.
            val email = users.findById(notification.userId)?.email
            channel.deliver(notification, Recipient(notification.userId, email))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DeliveryResult.Retryable("${e::class.simpleName}: ${e.message.orEmpty()}".take(300))
        }
    }

    private fun failed(notification: Notification, reason: String) = notification.copy(
        status = NotificationStatus.FAILED, attempts = notification.attempts + 1, lastError = reason.take(500), deliverAfter = null
    )

    companion object {
        const val MAX_ATTEMPTS = 4

        /** Wait before attempt n+1, after n failed attempts: 5 min, 30 min, 3 h. */
        fun backoff(failedAttempts: Int): Duration {
            require(failedAttempts >= 1) { "backoff is for after a failed attempt" }
            return Duration.ofMinutes(5).multipliedBy(Math.pow(6.0, (failedAttempts - 1).toDouble()).toLong())
        }
    }
}
