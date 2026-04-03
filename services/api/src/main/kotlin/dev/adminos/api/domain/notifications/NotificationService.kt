package dev.adminos.api.domain.notifications

import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Core notification service with channel routing and quiet hours logic.
 *
 * Accepts notification requests, checks user preferences, applies quiet hours,
 * and routes to enabled channels (push, email, in-app).
 */
class NotificationService(
    private val preferencesRepository: NotificationPreferencesRepository,
    private val notificationRepository: NotificationRepository,
    private val pushService: PushService
) {
    private val logger = LoggerFactory.getLogger(NotificationService::class.java)

    /**
     * Send a notification to a user, respecting their preferences and quiet hours.
     *
     * @param userId target user
     * @param type notification type (determines if critical)
     * @param title notification title
     * @param body notification body
     * @param data optional key-value data payload
     * @param userTimezone user's timezone for quiet hours calculation
     * @return list of created notification records (one per channel)
     */
    suspend fun send(
        userId: UUID,
        type: NotificationType,
        title: String,
        body: String,
        data: Map<String, String>? = null,
        userTimezone: String = "Asia/Kolkata"
    ): List<Notification> {
        val prefs = preferencesRepository.findByUserId(userId)
            ?: NotificationPreferences.defaultFor(userId).also { preferencesRepository.save(it) }

        // Check if this notification type is enabled
        if (!isTypeEnabled(type, prefs)) {
            logger.debug("Notification type {} disabled for user {}", type, userId)
            return emptyList()
        }

        val isCritical = isCriticalNotification(type)
        val userTime = try {
            LocalTime.now(ZoneId.of(userTimezone))
        } catch (e: Exception) {
            LocalTime.now(ZoneId.of("Asia/Kolkata")) // fallback to India timezone
        }
        val inQuietHours = isQuietHours(userTime, prefs.quietHoursStart, prefs.quietHoursEnd)

        // Non-critical notifications are held during quiet hours
        if (!isCritical && inQuietHours) {
            logger.info("Holding non-critical notification for user {} during quiet hours", userId)
            val held = Notification(
                userId = userId,
                type = type,
                title = title,
                body = body,
                data = data,
                channel = NotificationChannel.IN_APP,
                status = NotificationStatus.PENDING
            )
            notificationRepository.save(held)
            return listOf(held)
        }

        // Route to enabled channels
        val results = mutableListOf<Notification>()

        // In-app always gets a record
        val inApp = Notification(
            userId = userId,
            type = type,
            title = title,
            body = body,
            data = data,
            channel = NotificationChannel.IN_APP,
            status = NotificationStatus.SENT,
            sentAt = Instant.now()
        )
        results.add(notificationRepository.save(inApp))

        // Push notification
        if (prefs.pushNotifications) {
            try {
                val pushResult = pushService.sendToUser(userId, title, body, data)
                val pushNotif = Notification(
                    userId = userId,
                    type = type,
                    title = title,
                    body = body,
                    data = data,
                    channel = NotificationChannel.PUSH,
                    fcmMessageId = pushResult.messageId,
                    status = if (pushResult.success) NotificationStatus.SENT else NotificationStatus.FAILED,
                    sentAt = if (pushResult.success) Instant.now() else null
                )
                results.add(notificationRepository.save(pushNotif))
            } catch (e: Exception) {
                logger.error("Push notification failed for user {}: {}", userId, e.message)
            }
        }

        // Email notification
        if (prefs.emailNotifications) {
            val emailNotif = Notification(
                userId = userId,
                type = type,
                title = title,
                body = body,
                data = data,
                channel = NotificationChannel.EMAIL,
                status = NotificationStatus.SENT,
                sentAt = Instant.now()
            )
            results.add(notificationRepository.save(emailNotif))
            logger.info("Email notification queued for user {}", userId)
        }

        return results
    }

    companion object {
        /**
         * Check if the current time falls within quiet hours.
         * Handles midnight crossing correctly:
         *   - Normal range (e.g., 08:00-17:00): start <= time <= end
         *   - Midnight crossing (e.g., 22:00-08:00): time >= start OR time <= end
         */
        fun isQuietHours(userTime: LocalTime, start: LocalTime, end: LocalTime): Boolean {
            return if (start <= end) {
                userTime in start..end       // e.g., 08:00-17:00
            } else {
                userTime >= start || userTime <= end  // e.g., 22:00-08:00
            }
        }

        /**
         * Critical notifications (anomaly alerts) bypass quiet hours.
         */
        fun isCriticalNotification(type: NotificationType): Boolean =
            type == NotificationType.ANOMALY

        /**
         * Check if a notification type is enabled in user preferences.
         */
        fun isTypeEnabled(type: NotificationType, prefs: NotificationPreferences): Boolean = when (type) {
            NotificationType.BILL_REMINDER -> prefs.billReminders
            NotificationType.ANOMALY -> prefs.anomalyAlerts
            NotificationType.BRIEFING -> prefs.weeklyBriefing
            NotificationType.SUBSCRIPTION_FLAG -> prefs.subscriptionFlags
            NotificationType.OVERDUE -> prefs.billReminders
            NotificationType.NUDGE -> true  // nudges always enabled (system-level)
            NotificationType.GENERAL -> true
        }
    }
}
