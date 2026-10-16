package dev.adminos.api.domain.notifications.delivery

import dev.adminos.api.domain.notifications.Notification
import dev.adminos.api.domain.notifications.NotificationChannel
import dev.adminos.api.domain.notifications.NotificationPreferences

/**
 * The notification inbox inside AdminOS. Always on and silent, so it is never held for quiet
 * hours: the stored record *is* the delivery.
 */
class InAppChannel : DeliveryChannel {
    override val channel = NotificationChannel.IN_APP
    override fun isWantedBy(prefs: NotificationPreferences) = true
    override suspend fun deliver(notification: Notification, recipient: Recipient): DeliveryResult = DeliveryResult.Delivered()
}
