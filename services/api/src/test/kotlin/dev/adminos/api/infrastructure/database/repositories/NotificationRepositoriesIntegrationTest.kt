package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.notifications.*
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationRepositoriesIntegrationTest {

    private val preferences = ExposedNotificationPreferencesRepository()
    private val notifications = ExposedNotificationRepository()
    private val devices = ExposedDeviceRepository()

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    @Test
    fun `preferences round-trip including reminder days, weekday and quiet hours`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        preferences.save(NotificationPreferences(userId = userId, billReminderDays = listOf(7, 3, 1), briefingDay = Weekday.SUN,
            quietHoursStart = LocalTime.of(23, 0), quietHoursEnd = LocalTime.of(7, 30), emailNotifications = false))
        val found = assertNotNull(preferences.findByUserId(userId))
        assertEquals(listOf(7, 3, 1), found.billReminderDays)
        assertEquals(Weekday.SUN, found.briefingDay)
        assertEquals(LocalTime.of(23, 0), found.quietHoursStart)
        assertEquals(LocalTime.of(7, 30), found.quietHoursEnd)
        assertEquals(false, found.emailNotifications)

        for (day in Weekday.entries) {
            preferences.update(found.copy(briefingDay = day))
            assertEquals(day, preferences.findByUserId(userId)?.briefingDay)
        }
    }

    @Test
    fun `delivery state round-trips and due notifications are claimed once per lease (AOS-504)`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val now = java.time.Instant.parse("2030-01-01T00:00:00Z")
        fun pending(deliverAfter: java.time.Instant?) = Notification(userId = userId, type = NotificationType.BILL_REMINDER, title = "Bill due",
            body = "HDFC", channel = NotificationChannel.EMAIL, deliverAfter = deliverAfter, createdAt = now.minusSeconds(60))
        val due = notifications.save(pending(now.minusSeconds(1)))
        val notYet = notifications.save(pending(now.plusSeconds(3600)))
        notifications.update(due.copy(attempts = 2, lastError = "SMTP send failed: timeout"))
        assertEquals(2, notifications.findById(due.id)?.attempts)
        assertEquals("SMTP send failed: timeout", notifications.findById(due.id)?.lastError)

        val lease = java.time.Duration.ofMinutes(2)
        val claimed = notifications.claimDue(now, lease, limit = 10_000).filter { it.userId == userId }
        assertEquals(listOf(due.id), claimed.map { it.id })
        assertEquals(now.plus(lease), claimed.single().deliverAfter)
        assertEquals(emptyList(), notifications.claimDue(now.plusSeconds(60), lease, 10_000).filter { it.userId == userId })
        assertEquals(notYet.deliverAfter, notifications.findById(notYet.id)?.deliverAfter)
    }

    @Test
    fun `notification round-trips every type, channel and status`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        for (type in NotificationType.entries) for (channel in NotificationChannel.entries) {
            notifications.save(Notification(userId = userId, type = type, title = "t", body = "b", channel = channel, data = mapOf("bill_id" to "x")))
        }
        val all = notifications.findByUserId(userId, limit = 500)
        assertEquals(NotificationType.entries.size * NotificationChannel.entries.size, all.size)
        assertEquals("x", all.first().data?.get("bill_id"))
        assertEquals(NotificationChannel.entries.size, notifications.countByUserAndType(userId, NotificationType.BILL_DUE))

        val n = all.first()
        for (status in NotificationStatus.entries) {
            notifications.update(n.copy(status = status))
            assertEquals(status, notifications.findById(n.id)?.status)
        }
    }

    @Test
    fun `device round-trips and token can be removed`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val d = devices.save(Device(userId = userId, deviceType = "ios", deviceName = "iPhone", fcmToken = "tok", smsPermission = false))
        val found = assertNotNull(devices.findById(d.id))
        assertEquals("ios", found.deviceType)
        assertEquals("tok", found.fcmToken)

        devices.update(found.copy(appVersion = "1.2.0"))
        assertEquals("1.2.0", devices.findById(d.id)?.appVersion)
        devices.removeToken(d.id)
        assertNull(devices.findById(d.id)?.fcmToken)
        assertEquals(1, devices.findByUserId(userId).size)
    }
}
