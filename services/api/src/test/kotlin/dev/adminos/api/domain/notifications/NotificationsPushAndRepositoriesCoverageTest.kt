package dev.adminos.api.domain.notifications

import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The InMemory notification fakes and DTO mapping (AOS-312). */
class NotificationsPushAndRepositoriesCoverageTest {

    private val userId = UUID.randomUUID()
    private val devices = InMemoryDeviceRepository()
    private val notifs = InMemoryNotificationRepository()
    private val prefsRepo = InMemoryNotificationPreferencesRepository()

    private fun notification(type: NotificationType = NotificationType.GENERAL, createdAt: Instant = Instant.now(), user: UUID = userId,
                             status: NotificationStatus = NotificationStatus.PENDING) =
        Notification(userId = user, type = type, title = "Swiggy ₹349", body = "UPI debit", channel = NotificationChannel.IN_APP,
                     status = status, createdAt = createdAt)

    // ── InMemoryNotificationRepository ──

    @Test
    fun `findByUserId returns newest first and honours the limit`() = runBlocking<Unit> {
        val t = Instant.parse("2026-10-16T00:00:00Z")
        val old = notifs.save(notification(createdAt = t))
        val mid = notifs.save(notification(createdAt = t.plusSeconds(60)))
        val new = notifs.save(notification(createdAt = t.plusSeconds(120)))
        notifs.save(notification(user = UUID.randomUUID()))

        assertEquals(listOf(new, mid, old), notifs.findByUserId(userId, 50))
        assertEquals(listOf(new), notifs.findByUserId(userId, 1))
    }

    @Test
    fun `findById returns the saved notification or null`() = runBlocking<Unit> {
        val n = notifs.save(notification())

        assertEquals(n, notifs.findById(n.id))
        assertNull(notifs.findById(UUID.randomUUID()))
    }

    @Test
    fun `update replaces the stored notification`() = runBlocking<Unit> {
        val n = notifs.save(notification())

        notifs.update(n.copy(status = NotificationStatus.CLICKED))

        assertEquals(NotificationStatus.CLICKED, notifs.findById(n.id)!!.status)
    }

    @Test
    fun `countByUserAndType counts only that user and type`() = runBlocking<Unit> {
        notifs.save(notification(NotificationType.NUDGE))
        notifs.save(notification(NotificationType.NUDGE))
        notifs.save(notification(NotificationType.ANOMALY))
        notifs.save(notification(NotificationType.NUDGE, user = UUID.randomUUID()))

        assertEquals(2, notifs.countByUserAndType(userId, NotificationType.NUDGE))
    }

    @Test
    fun `findPending returns pending notifications oldest first`() = runBlocking<Unit> {
        val t = Instant.parse("2026-10-16T00:00:00Z")
        val later = notifs.save(notification(createdAt = t.plusSeconds(5)))
        val earlier = notifs.save(notification(createdAt = t))
        notifs.save(notification(status = NotificationStatus.SENT))

        assertEquals(listOf(earlier, later), notifs.findPending())
    }

    // ── InMemoryNotificationPreferencesRepository ──

    @Test
    fun `preferences update stores the change and refreshes updatedAt`() = runBlocking<Unit> {
        val p = prefsRepo.save(NotificationPreferences(userId = userId, updatedAt = Instant.parse("2020-01-01T00:00:00Z")))

        val updated = prefsRepo.update(p.copy(weeklyBriefing = false))

        assertFalse(updated.weeklyBriefing)
        assertNotEquals(p.updatedAt, updated.updatedAt)
        assertEquals(updated, prefsRepo.findByUserId(userId))
    }

    @Test
    fun `findByUserAndType filters on both user and type`() = runBlocking<Unit> {
        val mine = notifs.save(notification(NotificationType.NUDGE))
        notifs.save(notification(NotificationType.ANOMALY))
        notifs.save(notification(NotificationType.NUDGE, user = UUID.randomUUID()))

        assertEquals(listOf(mine), notifs.findByUserAndType(userId, NotificationType.NUDGE))
    }

    @Test
    fun `preferences lookup picks the right user among several`() = runBlocking<Unit> {
        prefsRepo.save(NotificationPreferences(userId = UUID.randomUUID()))
        val mine = prefsRepo.save(NotificationPreferences(userId = userId, briefingDay = Weekday.SAT))
        prefsRepo.save(NotificationPreferences(userId = UUID.randomUUID()))

        assertEquals(mine, prefsRepo.findByUserId(userId))
    }

    @Test
    fun `preferences lookup for an unknown user is null`() = runBlocking<Unit> {
        assertNull(prefsRepo.findByUserId(UUID.randomUUID()))
    }

    // ── InMemoryDeviceRepository ──

    @Test
    fun `device update stores the change and refreshes updatedAt`() = runBlocking<Unit> {
        val d = devices.save(Device(userId = userId, fcmToken = "a", updatedAt = Instant.parse("2020-01-01T00:00:00Z")))

        val updated = devices.update(d.copy(appVersion = "1.4.0"))

        assertEquals("1.4.0", devices.findById(d.id)!!.appVersion)
        assertNotEquals(d.updatedAt, updated.updatedAt)
    }

    @Test
    fun `removing a token for an unknown device is a no-op`() = runBlocking<Unit> {
        devices.removeToken(UUID.randomUUID())

        assertTrue(devices.findByUserId(userId).isEmpty())
    }

    // ── DTO mapping ──

    @Test
    fun `preferences response renders enums lowercase and times as ISO strings`() {
        val p = NotificationPreferences(userId = userId, briefingDay = Weekday.FRI, briefingTime = LocalTime.of(9, 30),
                                        quietHoursStart = LocalTime.of(23, 0), quietHoursEnd = LocalTime.of(6, 45))

        val r = NotificationPreferencesResponse.from(p)

        assertEquals("fri", r.briefingDay)
        assertEquals("09:30", r.briefingTime)
        assertEquals("23:00", r.quietHoursStart)
        assertEquals("06:45", r.quietHoursEnd)
        assertEquals(userId.toString(), r.userId)
        assertEquals(listOf(3, 1), r.billReminderDays)
    }

    @Test
    fun `notification response renders a null sentAt as null`() {
        val r = NotificationResponse.from(notification(NotificationType.BILL_DUE))

        assertNull(r.sentAt)
        assertEquals("bill_due", r.type)
        assertEquals("in_app", r.channel)
        assertEquals("pending", r.status)
    }

    @Test
    fun `notification response renders sentAt when present`() {
        val sent = Instant.parse("2026-10-16T04:30:00Z")

        val r = NotificationResponse.from(notification().copy(sentAt = sent, status = NotificationStatus.SENT))

        assertEquals("2026-10-16T04:30:00Z", r.sentAt)
        assertEquals("sent", r.status)
    }
}
