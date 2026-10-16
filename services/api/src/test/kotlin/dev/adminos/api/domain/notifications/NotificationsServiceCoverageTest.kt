package dev.adminos.api.domain.notifications

import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.notifications.delivery.DeliveryResult
import dev.adminos.api.domain.notifications.delivery.InAppChannel
import dev.adminos.api.domain.scheduling.TestClock
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** NotificationService.send (AOS-312, AOS-504): preferences, channels, quiet hours and immediate delivery, on a fixed clock. */
class NotificationsServiceCoverageTest {

    private val user = User(email = "pragun@example.com", googleId = "g-1")
    private val userId = user.id
    private val users = InMemoryUserRepository()
    private val prefsRepo = InMemoryNotificationPreferencesRepository()
    private val notifRepo = InMemoryNotificationRepository()
    /** 15:00 IST — outside the default 22:00–08:00 quiet hours. */
    private val clock = TestClock(Instant.parse("2026-10-16T09:30:00Z"))
    private val push = RecordingChannel(NotificationChannel.PUSH, wanted = { it.pushNotifications })
    private val email = RecordingChannel(NotificationChannel.EMAIL, wanted = { it.emailNotifications })
    private val service = notificationService(prefsRepo, notifRepo, listOf(InAppChannel(), push, email), users, clock)

    init { runBlocking { users.save(user) } }

    private suspend fun prefs(push: Boolean = true, email: Boolean = true, quiet: Pair<LocalTime, LocalTime> = LocalTime.of(22, 0) to LocalTime.of(8, 0)) =
        prefsRepo.save(NotificationPreferences(userId = userId, pushNotifications = push, emailNotifications = email,
            quietHoursStart = quiet.first, quietHoursEnd = quiet.second))

    private suspend fun send(type: NotificationType = NotificationType.GENERAL, tz: String = "Asia/Kolkata") =
        service.send(userId, type, "HDFC bill due", "₹4,560.00 due on 20 Oct", mapOf("entity_type" to "bill"), tz)

    @Test
    fun `missing preferences are created with defaults on first send`() = runBlocking<Unit> {
        send()
        assertEquals(NotificationPreferences.defaultFor(userId).billReminders, prefsRepo.findByUserId(userId)!!.billReminders)
    }

    @Test
    fun `a disabled type creates nothing`() = runBlocking<Unit> {
        prefsRepo.save(NotificationPreferences(userId = userId, billReminders = false))
        assertTrue(send(NotificationType.BILL_REMINDER).isEmpty())
        assertTrue(notifRepo.findByUserId(userId).isEmpty())
    }

    @Test
    fun `outside quiet hours every wanted channel is delivered at once, in-app first`() = runBlocking<Unit> {
        prefs()
        val results = send()
        assertEquals(listOf(NotificationChannel.IN_APP, NotificationChannel.PUSH, NotificationChannel.EMAIL), results.map { it.channel })
        assertTrue(results.all { it.status == NotificationStatus.SENT && it.sentAt == clock.now && it.attempts == 1 })
        assertEquals(listOf<String?>("pragun@example.com"), email.recipients)
        assertEquals("ref-1", results.last().fcmMessageId, "the provider reference is kept")
        assertEquals(results, results.map { notifRepo.findById(it.id) }, "what is returned is what is stored")
    }

    @Test
    fun `channels the user switched off get no record`() = runBlocking<Unit> {
        prefs(push = false, email = false)
        assertEquals(listOf(NotificationChannel.IN_APP), send().map { it.channel })
    }

    @Test
    fun `during quiet hours in-app shows at once and push and email wait for quiet hours to end`() = runBlocking<Unit> {
        clock.now = Instant.parse("2026-10-16T17:30:00Z") // 23:00 IST
        prefs()
        val (inApp, pushRecord, emailRecord) = send(NotificationType.BILL_REMINDER)
        assertEquals(NotificationStatus.SENT, inApp.status)
        val eightAmIst = Instant.parse("2026-10-17T02:30:00Z")
        listOf(pushRecord, emailRecord).forEach {
            assertEquals(NotificationStatus.PENDING, it.status)
            assertEquals(eightAmIst, it.deliverAfter)
            assertEquals(0, it.attempts)
        }
        assertTrue(push.recipients.isEmpty() && email.recipients.isEmpty())
    }

    @Test
    fun `an anomaly alert ignores quiet hours`() = runBlocking<Unit> {
        clock.now = Instant.parse("2026-10-16T17:30:00Z")
        prefs()
        assertTrue(send(NotificationType.ANOMALY).all { it.status == NotificationStatus.SENT })
    }

    @Test
    fun `quiet hours are judged in the user's own time zone`() = runBlocking<Unit> {
        prefs()
        // 09:30Z is 15:00 in India but 23:30 on Kiritimati (UTC+14, no DST): quiet there.
        val held = send(tz = "Pacific/Kiritimati").filter { it.status == NotificationStatus.PENDING }
        assertEquals(setOf(NotificationChannel.PUSH, NotificationChannel.EMAIL), held.map { it.channel }.toSet())
        assertEquals(Instant.parse("2026-10-16T18:00:00Z"), held.first().deliverAfter, "08:00 on Kiritimati next morning")
    }

    @Test
    fun `an invalid time zone falls back to India time`() = runBlocking<Unit> {
        prefs()
        assertTrue(send(tz = "Mars/Olympus_Mons").all { it.status == NotificationStatus.SENT })
    }

    @Test
    fun `a failed channel doesn't stop the others`() = runBlocking<Unit> {
        prefs()
        push.next = DeliveryResult.Retryable("push service down")
        val results = send()
        assertEquals(NotificationStatus.PENDING, results[1].status)
        assertEquals("push service down", results[1].lastError)
        assertEquals(NotificationStatus.SENT, results[2].status)
    }

    @Test
    fun `quiet hours end is the next local occurrence of the end time`() {
        val ist = NotificationService.DEFAULT_ZONE
        assertEquals(Instant.parse("2026-10-17T02:30:00Z"), NotificationService.quietHoursEnd(Instant.parse("2026-10-16T17:30:00Z"), ist, LocalTime.of(8, 0)))
        assertEquals(Instant.parse("2026-10-16T02:30:00Z"), NotificationService.quietHoursEnd(Instant.parse("2026-10-15T21:00:00Z"), ist, LocalTime.of(8, 0)), "02:30 IST → 08:00 the same day")
        assertEquals(Instant.parse("2026-10-17T02:30:00Z"), NotificationService.quietHoursEnd(Instant.parse("2026-10-16T02:30:00Z"), ist, LocalTime.of(8, 0)), "exactly at the end → tomorrow")
    }

    @Test
    fun `only anomaly is critical`() {
        val critical = NotificationType.entries.filter { NotificationService.isCriticalNotification(it) }

        assertEquals(listOf(NotificationType.ANOMALY), critical)
    }

    @Test
    fun `every type is enabled when all preference toggles are on`() {
        val on = NotificationPreferences(userId = userId)

        assertTrue(NotificationType.entries.all { NotificationService.isTypeEnabled(it, on) })
    }

    @Test
    fun `with all toggles off only system-level types stay enabled`() {
        val off = NotificationPreferences(
            userId = userId, weeklyBriefing = false, billReminders = false,
            anomalyAlerts = false, subscriptionFlags = false
        )

        val enabled = NotificationType.entries.filter { NotificationService.isTypeEnabled(it, off) }.toSet()

        assertEquals(setOf(NotificationType.NUDGE, NotificationType.GENERAL, NotificationType.SYNC_COMPLETE, NotificationType.SYSTEM), enabled)
    }

    @Test
    fun `overdue follows the bill reminders toggle`() {
        assertFalse(NotificationService.isTypeEnabled(NotificationType.OVERDUE, NotificationPreferences(userId = userId, billReminders = false)))
    }

    @Test
    fun `a time just outside a normal quiet range is not quiet`() {
        assertFalse(NotificationService.isQuietHours(LocalTime.of(7, 59), LocalTime.of(8, 0), LocalTime.of(17, 0)))
        assertFalse(NotificationService.isQuietHours(LocalTime.of(17, 1), LocalTime.of(8, 0), LocalTime.of(17, 0)))
    }

    @Test
    fun `a zero-length quiet range only matches its exact instant`() {
        val t = LocalTime.of(13, 0)

        assertTrue(NotificationService.isQuietHours(t, t, t))
        assertFalse(NotificationService.isQuietHours(t.plusSeconds(1), t, t))
    }
}
