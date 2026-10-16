package dev.adminos.api.domain.notifications

import dev.adminos.api.domain.notifications.NotificationsFixtures.ALWAYS_QUIET
import dev.adminos.api.domain.notifications.NotificationsFixtures.neverQuietNow
import dev.adminos.api.domain.notifications.NotificationsFixtures.prefs
import kotlinx.coroutines.runBlocking
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** NotificationService.send routing (AOS-312): preferences, quiet hours, channels and failure handling. */
class NotificationsServiceCoverageTest {

    private val userId = UUID.randomUUID()
    private val prefsRepo = InMemoryNotificationPreferencesRepository()
    private val notifRepo = InMemoryNotificationRepository()
    private val deviceRepo = InMemoryDeviceRepository()
    private val service = NotificationService(prefsRepo, notifRepo, PushService(deviceRepo))

    private suspend fun send(type: NotificationType = NotificationType.GENERAL, tz: String = "Asia/Kolkata") =
        service.send(userId, type, "HDFC bill due", "₹4,560.00 due on 20 Oct", mapOf("entity_type" to "bill"), tz)

    @Test
    fun `missing preferences are created with defaults on first send`() = runBlocking<Unit> {
        send()

        val saved = prefsRepo.findByUserId(userId)
        assertNotNull(saved)
        assertEquals(NotificationPreferences.defaultFor(userId).copy(id = saved.id, createdAt = saved.createdAt, updatedAt = saved.updatedAt), saved)
    }

    @Test
    fun `a disabled type creates no notifications`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId, billReminders = false))

        val results = send(NotificationType.BILL_DUE)

        assertTrue(results.isEmpty())
        assertTrue(notifRepo.findByUserId(userId).isEmpty())
    }

    @Test
    fun `outside quiet hours with push and email off only an in-app record is sent`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId))

        val results = send()

        assertEquals(listOf(NotificationChannel.IN_APP), results.map { it.channel })
        assertEquals(NotificationStatus.SENT, results[0].status)
        assertNotNull(results[0].sentAt)
        assertEquals(mapOf("entity_type" to "bill"), results[0].data)
    }

    @Test
    fun `push to a registered device is recorded as sent with the FCM message id`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId, push = true))
        deviceRepo.save(Device(userId = userId, fcmToken = "fcm-token-pixel-8"))

        val push = send().single { it.channel == NotificationChannel.PUSH }

        assertEquals(NotificationStatus.SENT, push.status)
        assertTrue(push.fcmMessageId!!.startsWith("fcm_"))
        assertNotNull(push.sentAt)
    }

    @Test
    fun `push without any device is recorded as failed with no sent time`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId, push = true))

        val push = send().single { it.channel == NotificationChannel.PUSH }

        assertEquals(NotificationStatus.FAILED, push.status)
        assertNull(push.sentAt)
        assertNull(push.fcmMessageId)
    }

    @Test
    fun `a push service error is swallowed and other channels still go out`() = runBlocking<Unit> {
        val failing = NotificationService(prefsRepo, notifRepo, PushService(FailingDeviceRepository()))
        prefsRepo.save(prefs(userId, push = true, email = true))

        val results = failing.send(userId, NotificationType.GENERAL, "t", "b")

        assertEquals(listOf(NotificationChannel.IN_APP, NotificationChannel.EMAIL), results.map { it.channel })
    }

    @Test
    fun `email enabled adds a sent email record`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId, email = true))

        val email = send().single { it.channel == NotificationChannel.EMAIL }

        assertEquals(NotificationStatus.SENT, email.status)
        assertNotNull(email.sentAt)
    }

    @Test
    fun `all channels enabled produce in-app, push and email records in that order`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId, push = true, email = true))
        deviceRepo.save(Device(userId = userId, fcmToken = "fcm-token"))

        val results = send()

        assertEquals(listOf(NotificationChannel.IN_APP, NotificationChannel.PUSH, NotificationChannel.EMAIL), results.map { it.channel })
        assertEquals(3, notifRepo.findByUserId(userId).size)
    }

    @Test
    fun `a non-critical notification in quiet hours is held as one pending in-app record`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId, window = ALWAYS_QUIET, push = true, email = true))

        val results = send(NotificationType.BRIEFING)

        assertEquals(1, results.size)
        assertEquals(NotificationChannel.IN_APP, results[0].channel)
        assertEquals(NotificationStatus.PENDING, results[0].status)
        assertNull(results[0].sentAt)
        assertEquals(results, notifRepo.findPending())
    }

    @Test
    fun `an anomaly alert in quiet hours is delivered on every enabled channel`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId, window = ALWAYS_QUIET, email = true))

        val results = send(NotificationType.ANOMALY)

        assertEquals(listOf(NotificationChannel.IN_APP, NotificationChannel.EMAIL), results.map { it.channel })
        assertTrue(results.all { it.status == NotificationStatus.SENT })
    }

    @Test
    fun `an anomaly alert outside quiet hours is delivered`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId))

        val results = send(NotificationType.ANOMALY)

        assertEquals(NotificationStatus.SENT, results.single().status)
    }

    @Test
    fun `an invalid user timezone falls back to India time`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId, window = neverQuietNow(ZoneId.of("Asia/Kolkata"))))

        val results = send(tz = "Mars/Olympus_Mons")

        assertEquals(NotificationStatus.SENT, results.single().status)
    }

    @Test
    fun `quiet hours are evaluated in the user's own timezone`() = runBlocking<Unit> {
        val london = ZoneId.of("Europe/London")
        val nowInLondon = LocalTime.now(london)
        prefsRepo.save(prefs(userId, window = nowInLondon.minusMinutes(30) to nowInLondon.plusMinutes(30)))

        val results = send(tz = "Europe/London")

        assertEquals(NotificationStatus.PENDING, results.single().status)
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
