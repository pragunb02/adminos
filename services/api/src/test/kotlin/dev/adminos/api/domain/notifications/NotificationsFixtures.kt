package dev.adminos.api.domain.notifications

import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Shared helpers for the AOS-312 notifications coverage tests. */
internal object NotificationsFixtures {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    /** A quiet-hours window that covers every instant of the day. */
    val ALWAYS_QUIET: Pair<LocalTime, LocalTime> = LocalTime.MIDNIGHT to LocalTime.MAX

    /** A one-hour quiet window starting 6h from now, so "now" is never inside it during a test run. */
    fun neverQuietNow(zone: ZoneId = IST): Pair<LocalTime, LocalTime> {
        val now = LocalTime.now(zone)
        return now.plusHours(6) to now.plusHours(7)
    }

    fun prefs(
        userId: UUID,
        window: Pair<LocalTime, LocalTime> = neverQuietNow(),
        push: Boolean = false,
        email: Boolean = false,
        billReminders: Boolean = true,
        billReminderDays: List<Int> = listOf(3, 1),
    ) = NotificationPreferences(
        userId = userId,
        quietHoursStart = window.first,
        quietHoursEnd = window.second,
        pushNotifications = push,
        emailNotifications = email,
        billReminders = billReminders,
        billReminderDays = billReminderDays,
    )
}

/** A notification service on in-memory parts; in-app only unless [channels] says otherwise (AOS-504). */
internal fun notificationService(
    prefs: NotificationPreferencesRepository,
    notifications: NotificationRepository,
    channels: List<dev.adminos.api.domain.notifications.delivery.DeliveryChannel> = listOf(dev.adminos.api.domain.notifications.delivery.InAppChannel()),
    users: dev.adminos.api.domain.identity.UserRepository = dev.adminos.api.domain.identity.InMemoryUserRepository(),
    clock: java.time.Clock = java.time.Clock.systemUTC(),
) = NotificationService(prefs, notifications, dev.adminos.api.domain.notifications.delivery.NotificationDispatcher(channels, users, notifications, clock), clock)

/** Notification repository that fails lookups for one user only. */
internal class FailingForUserNotificationRepository(
    private val failingUser: UUID,
    private val delegate: InMemoryNotificationRepository = InMemoryNotificationRepository(),
) : NotificationRepository by delegate {
    override suspend fun findByUserAndType(userId: UUID, type: NotificationType): List<Notification> {
        if (userId == failingUser) error("db timeout")
        return delegate.findByUserAndType(userId, type)
    }
}

/** Preferences repository that fails lookups for one user only. */
internal class FailingForUserPreferencesRepository(
    private val failingUser: UUID,
    private val delegate: InMemoryNotificationPreferencesRepository = InMemoryNotificationPreferencesRepository(),
) : NotificationPreferencesRepository by delegate {
    override suspend fun findByUserId(userId: UUID): NotificationPreferences? {
        if (userId == failingUser) error("db timeout")
        return delegate.findByUserId(userId)
    }
}

/** A channel that records who it was asked to reach and answers with [next] (AOS-504 tests). */
internal class RecordingChannel(
    override val channel: NotificationChannel,
    private val wanted: (NotificationPreferences) -> Boolean = { true },
    var next: dev.adminos.api.domain.notifications.delivery.DeliveryResult =
        dev.adminos.api.domain.notifications.delivery.DeliveryResult.Delivered("ref-1"),
) : dev.adminos.api.domain.notifications.delivery.DeliveryChannel {
    val recipients = mutableListOf<String?>()
    override fun isWantedBy(prefs: NotificationPreferences) = wanted(prefs)
    override suspend fun deliver(notification: Notification, recipient: dev.adminos.api.domain.notifications.delivery.Recipient):
        dev.adminos.api.domain.notifications.delivery.DeliveryResult {
        recipients += recipient.email
        return next
    }
}
