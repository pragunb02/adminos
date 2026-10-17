package dev.adminos.api.domain.notifications

import dev.adminos.api.domain.notifications.delivery.NotificationDispatcher
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Turns "tell the user X" into one stored notification per channel the user wants, and delivers
 * them (AOS-504):
 * 1. the type must be switched on in the user's preferences;
 * 2. in-app is delivered at once (it is silent);
 * 3. push and email are delivered at once, or — for non-critical types during quiet hours — held
 *    until quiet hours end and then sent by [PendingNotificationsJob].
 */
class NotificationService(
    private val preferencesRepository: NotificationPreferencesRepository,
    private val notificationRepository: NotificationRepository,
    private val dispatcher: NotificationDispatcher,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = LoggerFactory.getLogger(NotificationService::class.java)

    /**
     * @param userTimezone the user's zone for quiet hours; an invalid zone falls back to India time (logged)
     * @return the stored notifications, one per channel, after any immediate delivery
     */
    suspend fun send(
        userId: UUID,
        type: NotificationType,
        title: String,
        body: String,
        data: Map<String, String>? = null,
        userTimezone: String = DEFAULT_ZONE.id
    ): List<Notification> {
        val prefs = preferencesRepository.findByUserId(userId)
            ?: NotificationPreferences.defaultFor(userId).also { preferencesRepository.save(it) }

        if (!isTypeEnabled(type, prefs)) {
            logger.debug("Notification type {} disabled for user {}", type, userId)
            return emptyList()
        }

        val now = clock.instant()
        val zone = zoneOrDefault(userTimezone, userId)
        val heldUntil = if (!isCriticalNotification(type) && isQuietHours(now.atZone(zone).toLocalTime(), prefs.quietHoursStart, prefs.quietHoursEnd))
            quietHoursEnd(now, zone, prefs.quietHoursEnd) else null

        return dispatcher.channelsFor(prefs).map { channel ->
            val holdThisOne = heldUntil != null && channel != NotificationChannel.IN_APP
            val stored = notificationRepository.save(Notification(
                userId = userId, type = type, title = title, body = body, data = data, channel = channel,
                status = NotificationStatus.PENDING, createdAt = now,
                // Held: wait for quiet hours to end. Sending now: a short lease, so the pending job won't also
                // pick it up — and will, if this process dies before recording the outcome.
                deliverAfter = if (holdThisOne) heldUntil else now.plus(SEND_LEASE)
            ))
            if (holdThisOne) {
                logger.info("Holding {} {} for user {} until quiet hours end at {}", channel, type, userId, heldUntil)
                stored
            } else {
                dispatcher.deliver(stored)
            }
        }
    }

    private fun zoneOrDefault(id: String, userId: UUID): ZoneId = try {
        ZoneId.of(id)
    } catch (e: DateTimeException) {
        logger.warn("User {} has an invalid time zone '{}'; using {} for quiet hours", userId, id, DEFAULT_ZONE)
        DEFAULT_ZONE
    }

    companion object {
        val DEFAULT_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

        /** How long an immediate send owns its record before the pending job may retry it. */
        val SEND_LEASE: Duration = Duration.ofMinutes(2)

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

        /** The next time the user's local clock shows [end] after [now] — when held notifications go out. */
        fun quietHoursEnd(now: Instant, zone: ZoneId, end: LocalTime): Instant {
            val local = now.atZone(zone)
            val today = local.toLocalDate().atTime(end).atZone(zone)
            return (if (today.toInstant().isAfter(now)) today else today.plusDays(1)).toInstant()
        }

        /**
         * Critical notifications (anomaly alerts) bypass quiet hours.
         */
        fun isCriticalNotification(type: NotificationType): Boolean =
            type == NotificationType.ANOMALY || type == NotificationType.SECURITY

        /**
         * Check if a notification type is enabled in user preferences.
         */
        fun isTypeEnabled(type: NotificationType, prefs: NotificationPreferences): Boolean = when (type) {
            NotificationType.BILL_REMINDER, NotificationType.BILL_DUE -> prefs.billReminders
            NotificationType.ANOMALY -> prefs.anomalyAlerts
            NotificationType.BRIEFING -> prefs.weeklyBriefing
            NotificationType.SUBSCRIPTION_FLAG, NotificationType.SUBSCRIPTION_FLAGGED -> prefs.subscriptionFlags
            NotificationType.OVERDUE -> prefs.billReminders
            NotificationType.NUDGE -> true  // nudges always enabled (system-level)
            NotificationType.GENERAL, NotificationType.SYNC_COMPLETE, NotificationType.SYSTEM -> true
            NotificationType.SECURITY -> true // sign-in alerts can't be switched off (AOS-620)
        }
    }
}
