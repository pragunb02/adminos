package dev.adminos.api.domain.notifications

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationServiceTest {

    private lateinit var prefsRepo: InMemoryNotificationPreferencesRepository
    private lateinit var notifRepo: InMemoryNotificationRepository
    private lateinit var deviceRepo: InMemoryDeviceRepository
    private lateinit var pushService: PushService
    private lateinit var notificationService: NotificationService

    private val testUserId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        prefsRepo = InMemoryNotificationPreferencesRepository()
        notifRepo = InMemoryNotificationRepository()
        deviceRepo = InMemoryDeviceRepository()
        pushService = PushService(deviceRepo)
        notificationService = NotificationService(prefsRepo, notifRepo, pushService)
    }

    // ── Quiet Hours Tests ──

    @Test
    fun `quiet hours normal range - time inside is quiet`() {
        // 08:00-17:00, check 12:00 → quiet
        val result = NotificationService.isQuietHours(
            LocalTime.of(12, 0),
            LocalTime.of(8, 0),
            LocalTime.of(17, 0)
        )
        assertTrue(result)
    }

    @Test
    fun `quiet hours normal range - time outside is not quiet`() {
        // 08:00-17:00, check 20:00 → not quiet
        val result = NotificationService.isQuietHours(
            LocalTime.of(20, 0),
            LocalTime.of(8, 0),
            LocalTime.of(17, 0)
        )
        assertFalse(result)
    }

    @Test
    fun `quiet hours midnight crossing - late night is quiet`() {
        // 22:00-08:00, check 23:00 → quiet
        val result = NotificationService.isQuietHours(
            LocalTime.of(23, 0),
            LocalTime.of(22, 0),
            LocalTime.of(8, 0)
        )
        assertTrue(result)
    }

    @Test
    fun `quiet hours midnight crossing - early morning is quiet`() {
        // 22:00-08:00, check 05:00 → quiet
        val result = NotificationService.isQuietHours(
            LocalTime.of(5, 0),
            LocalTime.of(22, 0),
            LocalTime.of(8, 0)
        )
        assertTrue(result)
    }

    @Test
    fun `quiet hours midnight crossing - daytime is not quiet`() {
        // 22:00-08:00, check 14:00 → not quiet
        val result = NotificationService.isQuietHours(
            LocalTime.of(14, 0),
            LocalTime.of(22, 0),
            LocalTime.of(8, 0)
        )
        assertFalse(result)
    }

    @Test
    fun `quiet hours at exact start boundary is quiet`() {
        val result = NotificationService.isQuietHours(
            LocalTime.of(22, 0),
            LocalTime.of(22, 0),
            LocalTime.of(8, 0)
        )
        assertTrue(result)
    }

    @Test
    fun `quiet hours at exact end boundary is quiet`() {
        val result = NotificationService.isQuietHours(
            LocalTime.of(8, 0),
            LocalTime.of(22, 0),
            LocalTime.of(8, 0)
        )
        assertTrue(result)
    }

    // ── Critical Notification Bypass Tests ──

    @Test
    fun `anomaly notification is critical`() {
        assertTrue(NotificationService.isCriticalNotification(NotificationType.ANOMALY))
    }

    @Test
    fun `bill reminder is not critical`() {
        assertFalse(NotificationService.isCriticalNotification(NotificationType.BILL_REMINDER))
    }

    @Test
    fun `briefing is not critical`() {
        assertFalse(NotificationService.isCriticalNotification(NotificationType.BRIEFING))
    }

    @Test
    fun `critical notification bypasses quiet hours and is sent`() = runBlocking {
        // Set up preferences with quiet hours active now (all day)
        val prefs = NotificationPreferences(
            userId = testUserId,
            quietHoursStart = LocalTime.of(0, 0),
            quietHoursEnd = LocalTime.of(23, 59),
            anomalyAlerts = true,
            pushNotifications = false,
            emailNotifications = false
        )
        prefsRepo.save(prefs)

        val results = notificationService.send(
            userId = testUserId,
            type = NotificationType.ANOMALY,
            title = "Suspicious transaction",
            body = "₹50,000 charge at 3am"
        )

        // Should be sent (not held) because anomaly is critical
        assertTrue(results.isNotEmpty())
        assertTrue(results.any { it.status == NotificationStatus.SENT })
    }

    @Test
    fun `non-critical notification is held during quiet hours`() = runBlocking {
        // Set up preferences with quiet hours covering all day
        val prefs = NotificationPreferences(
            userId = testUserId,
            quietHoursStart = LocalTime.of(0, 0),
            quietHoursEnd = LocalTime.of(23, 59),
            billReminders = true,
            pushNotifications = false,
            emailNotifications = false
        )
        prefsRepo.save(prefs)

        val results = notificationService.send(
            userId = testUserId,
            type = NotificationType.BILL_REMINDER,
            title = "Bill due tomorrow",
            body = "HDFC credit card bill"
        )

        // Should be held (pending) because it's non-critical during quiet hours
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.status == NotificationStatus.PENDING })
    }

    // ── Channel Routing Tests ──

    @Test
    fun `notification type disabled returns empty`() = runBlocking {
        val prefs = NotificationPreferences(
            userId = testUserId,
            billReminders = false,
            quietHoursStart = LocalTime.of(23, 0),
            quietHoursEnd = LocalTime.of(5, 0)
        )
        prefsRepo.save(prefs)

        val results = notificationService.send(
            userId = testUserId,
            type = NotificationType.BILL_REMINDER,
            title = "Bill due",
            body = "Test"
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun `channel routing respects push disabled`() = runBlocking {
        val prefs = NotificationPreferences(
            userId = testUserId,
            pushNotifications = false,
            emailNotifications = true,
            // Use quiet hours that won't match current time (noon to 12:01)
            quietHoursStart = LocalTime.of(12, 0),
            quietHoursEnd = LocalTime.of(12, 1)
        )
        prefsRepo.save(prefs)

        val results = notificationService.send(
            userId = testUserId,
            type = NotificationType.GENERAL,
            title = "Test",
            body = "Test body"
        )

        // Should have in-app + email, but no push
        val channels = results.map { it.channel }
        assertTrue(NotificationChannel.IN_APP in channels)
        assertTrue(NotificationChannel.EMAIL in channels)
        assertFalse(NotificationChannel.PUSH in channels)
    }

    @Test
    fun `channel routing respects email disabled`() = runBlocking {
        val prefs = NotificationPreferences(
            userId = testUserId,
            pushNotifications = false,
            emailNotifications = false,
            quietHoursStart = LocalTime.of(2, 0),
            quietHoursEnd = LocalTime.of(3, 0)
        )
        prefsRepo.save(prefs)

        val results = notificationService.send(
            userId = testUserId,
            type = NotificationType.GENERAL,
            title = "Test",
            body = "Test body"
        )

        // Should only have in-app
        assertEquals(1, results.size)
        assertEquals(NotificationChannel.IN_APP, results[0].channel)
    }

    @Test
    fun `isTypeEnabled returns false for disabled bill reminders`() {
        val prefs = NotificationPreferences(userId = testUserId, billReminders = false)
        assertFalse(NotificationService.isTypeEnabled(NotificationType.BILL_REMINDER, prefs))
    }

    @Test
    fun `isTypeEnabled returns true for enabled anomaly alerts`() {
        val prefs = NotificationPreferences(userId = testUserId, anomalyAlerts = true)
        assertTrue(NotificationService.isTypeEnabled(NotificationType.ANOMALY, prefs))
    }
}
