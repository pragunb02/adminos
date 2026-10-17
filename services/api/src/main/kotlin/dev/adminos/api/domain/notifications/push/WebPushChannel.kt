package dev.adminos.api.domain.notifications.push

import dev.adminos.api.domain.notifications.Notification
import dev.adminos.api.domain.notifications.NotificationChannel
import dev.adminos.api.domain.notifications.NotificationPreferences
import dev.adminos.api.domain.notifications.NotificationType
import dev.adminos.api.domain.notifications.delivery.DeliveryChannel
import dev.adminos.api.domain.notifications.delivery.DeliveryResult
import dev.adminos.api.domain.notifications.delivery.Recipient
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Push to every device the user turned push on for (AOS-510). One accepted device counts as
 * delivered (no retry, so the others' failures can't cause duplicates on it); gone devices are
 * removed; if none accepted, any retryable answer makes the whole push retryable.
 */
class WebPushChannel(
    private val subscriptions: PushSubscriptionRepository,
    private val sender: PushSender,
    private val clock: Clock = Clock.systemUTC()
) : DeliveryChannel {
    private val logger = LoggerFactory.getLogger(WebPushChannel::class.java)

    override val channel = NotificationChannel.PUSH

    override fun isWantedBy(prefs: NotificationPreferences) = prefs.pushNotifications

    override suspend fun deliver(notification: Notification, recipient: Recipient): DeliveryResult {
        val devices = subscriptions.findByUserId(recipient.userId)
        if (devices.isEmpty()) return DeliveryResult.Permanent("No device has push turned on")
        return send(devices, messageFor(notification))
    }

    /** Sends [message] to [devices] and folds their answers into one result. */
    suspend fun send(devices: List<PushSubscription>, message: PushMessage): DeliveryResult {
        var accepted = 0
        val retryable = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        for (device in devices) {
            when (val result = sender.send(device, message)) {
                PushSendResult.Accepted -> { accepted++; subscriptions.markSucceeded(device.id, clock.instant()) }
                PushSendResult.Gone -> {
                    logger.info("Push subscription {} at {} is gone; removing it", device.id, device.endpoint)
                    subscriptions.deleteById(device.id)
                    rejected += "a device unsubscribed"
                }
                is PushSendResult.Retryable -> retryable += result.reason
                is PushSendResult.Rejected -> rejected += result.reason
            }
        }
        return when {
            accepted > 0 -> DeliveryResult.Delivered("$accepted of ${devices.size} devices")
            retryable.isNotEmpty() -> DeliveryResult.Retryable(retryable.distinct().joinToString("; "))
            else -> DeliveryResult.Permanent(rejected.distinct().joinToString("; "))
        }
    }

    companion object {
        /** Where a tap opens, from the notification's data (e.g. a bill → /bills). */
        fun messageFor(n: Notification) = PushMessage(
            title = n.title,
            body = n.body.take(300),
            url = when (n.data?.get("entity_type")) {
                "bill" -> "/bills"
                "anomaly" -> n.data["entity_id"]?.let { "/anomalies/$it" } ?: "/"
                "subscription" -> "/subscriptions"
                "briefing" -> "/briefings"
                "coach" -> "/leaks"
                else -> "/"
            },
            tag = n.data?.get("entity_id")?.let { "${n.type.name.lowercase()}-$it" },
            urgent = n.type == NotificationType.ANOMALY
        )
    }
}
