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

/** Device repository whose lookups fail, to exercise PushService error handling. */
internal class FailingDeviceRepository : DeviceRepository by InMemoryDeviceRepository() {
    override suspend fun findByUserId(userId: UUID): List<Device> = error("FCM device store unavailable")
}

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
