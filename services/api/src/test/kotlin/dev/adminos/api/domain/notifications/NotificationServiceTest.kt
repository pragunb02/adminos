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
    private lateinit var notificationService: NotificationService

    private val testUserId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        prefsRepo = InMemoryNotificationPreferencesRepository()
        notifRepo = InMemoryNotificationRepository()
        notificationService = notificationService(prefsRepo, notifRepo)
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

    // Sending, holding and delivery: NotificationsServiceCoverageTest and NotificationDeliveryTest (AOS-504, fixed clock).

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

    // ── SQL alias types (AOS-101) ──

    @Test
    fun `isTypeEnabled treats BILL_DUE like bill reminders`() {
        val prefs = NotificationPreferences(userId = testUserId, billReminders = false)
        assertFalse(NotificationService.isTypeEnabled(NotificationType.BILL_DUE, prefs))
    }

    @Test
    fun `isTypeEnabled treats SUBSCRIPTION_FLAGGED like subscription flags`() {
        val prefs = NotificationPreferences(userId = testUserId, subscriptionFlags = false)
        assertFalse(NotificationService.isTypeEnabled(NotificationType.SUBSCRIPTION_FLAGGED, prefs))
    }

    @Test
    fun `isTypeEnabled always enables system-level types`() {
        val prefs = NotificationPreferences(
            userId = testUserId,
            weeklyBriefing = false,
            billReminders = false,
            anomalyAlerts = false,
            subscriptionFlags = false
        )
        assertTrue(NotificationService.isTypeEnabled(NotificationType.SYNC_COMPLETE, prefs))
        assertTrue(NotificationService.isTypeEnabled(NotificationType.SYSTEM, prefs))
    }
}
