package dev.adminos.api.domain.notifications

import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.notifications.delivery.DeliveryResult
import dev.adminos.api.domain.notifications.delivery.EmailChannel
import dev.adminos.api.domain.notifications.delivery.EmailMessage
import dev.adminos.api.domain.notifications.delivery.EmailSendException
import dev.adminos.api.domain.notifications.delivery.EmailSender
import dev.adminos.api.domain.notifications.delivery.InAppChannel
import dev.adminos.api.domain.notifications.delivery.NotificationDispatcher
import dev.adminos.api.domain.notifications.delivery.Recipient
import dev.adminos.api.domain.scheduling.JobName
import dev.adminos.api.domain.scheduling.Schedule
import dev.adminos.api.domain.scheduling.TestClock
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Delivery outcomes, retries, the email channel and the pending-notifications job (AOS-504). */
class NotificationDeliveryTest {
    private val clock = TestClock(Instant.parse("2026-10-16T09:30:00Z"))
    private val users = InMemoryUserRepository()
    private val repo = InMemoryNotificationRepository()
    private val user = runBlocking { users.save(User(email = "owner@example.com", googleId = "g-1")) }
    private val push = RecordingChannel(NotificationChannel.PUSH)
    private val dispatcher = NotificationDispatcher(listOf(InAppChannel(), push), users, repo, clock)

    private suspend fun stored(channel: NotificationChannel = NotificationChannel.PUSH, attempts: Int = 0, deliverAfter: Instant? = null,
                               userId: UUID = user.id, createdAt: Instant = clock.now) =
        repo.save(Notification(userId = userId, type = NotificationType.BILL_REMINDER, title = "Bill due in 3 days",
            body = "HDFC bill of ₹4560.00 is due on 2026-10-19", channel = channel, attempts = attempts,
            deliverAfter = deliverAfter, createdAt = createdAt))

    // ── Dispatcher ──

    @Test
    fun `delivered means sent, with the provider reference`() = runBlocking<Unit> {
        val n = dispatcher.deliver(stored())
        assertEquals(NotificationStatus.SENT, n.status)
        assertEquals(clock.now, n.sentAt)
        assertEquals("ref-1", n.fcmMessageId)
        assertNull(n.deliverAfter)
        assertEquals(n, repo.findById(n.id))
    }

    @Test
    fun `a retryable failure waits 5 min, then 30 min, then 3 h, then gives up`() = runBlocking<Unit> {
        push.next = DeliveryResult.Retryable("503 from push service")
        var n = stored()
        listOf(Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(3)).forEachIndexed { i, wait ->
            n = dispatcher.deliver(n)
            assertEquals(NotificationStatus.PENDING, n.status, "after attempt ${i + 1}")
            assertEquals(clock.now.plus(wait), n.deliverAfter)
            assertEquals("503 from push service", n.lastError)
        }
        n = dispatcher.deliver(n)
        assertEquals(NotificationStatus.FAILED, n.status)
        assertEquals(4, n.attempts)
        assertEquals("Gave up after 4 attempts: 503 from push service", n.lastError)
    }

    @Test
    fun `a permanent failure fails at once`() = runBlocking<Unit> {
        push.next = DeliveryResult.Permanent("subscription expired")
        val n = dispatcher.deliver(stored())
        assertEquals(NotificationStatus.FAILED, n.status)
        assertEquals("subscription expired", n.lastError)
        assertEquals(1, n.attempts)
    }

    @Test
    fun `a channel that throws is retried like a retryable failure`() = runBlocking<Unit> {
        val throwing = object : dev.adminos.api.domain.notifications.delivery.DeliveryChannel by RecordingChannel(NotificationChannel.EMAIL) {
            override suspend fun deliver(notification: Notification, recipient: Recipient): DeliveryResult = throw IOException("socket closed")
        }
        val d = NotificationDispatcher(listOf(InAppChannel(), throwing), users, repo, clock)
        val n = d.deliver(stored(NotificationChannel.EMAIL))
        assertEquals(NotificationStatus.PENDING, n.status)
        assertEquals("IOException: socket closed", n.lastError)
    }

    @Test
    fun `a record for a channel that is no longer configured fails with the reason`() = runBlocking<Unit> {
        val n = dispatcher.deliver(stored(NotificationChannel.EMAIL))
        assertEquals(NotificationStatus.FAILED, n.status)
        assertEquals("The email channel is not configured", n.lastError)
    }

    @Test
    fun `without a user record there is no address, but in-app still works`() = runBlocking<Unit> {
        val nobody = UUID.randomUUID()
        assertEquals(NotificationStatus.SENT, dispatcher.deliver(stored(NotificationChannel.IN_APP, userId = nobody)).status)
        dispatcher.deliver(stored(userId = nobody))
        assertEquals(listOf<String?>(null), push.recipients)
    }

    @Test
    fun `channels must be unique and include in-app`() {
        assertFailsWith<IllegalArgumentException> { NotificationDispatcher(listOf(push), users, repo, clock) }
        assertFailsWith<IllegalArgumentException> { NotificationDispatcher(listOf(InAppChannel(), InAppChannel()), users, repo, clock) }
        assertFailsWith<IllegalArgumentException> { NotificationDispatcher.backoff(0) }
    }

    @Test
    fun `the user's channels are the configured ones they want, in-app first`() {
        val email = RecordingChannel(NotificationChannel.EMAIL, wanted = { it.emailNotifications })
        val d = NotificationDispatcher(listOf(email, push, InAppChannel()), users, repo, clock)
        val prefs = NotificationPreferences(userId = user.id, emailNotifications = false)
        assertEquals(listOf(NotificationChannel.IN_APP, NotificationChannel.PUSH), d.channelsFor(prefs))
    }

    // ── Email channel ──

    private class FakeSender(var failure: EmailSendException? = null) : EmailSender {
        val sent = mutableListOf<EmailMessage>()
        override suspend fun send(message: EmailMessage): String? {
            failure?.let { throw it }
            sent += message
            return "<msg-1@example.com>"
        }
    }

    private val sender = FakeSender()
    private val emailChannel = EmailChannel(sender, "https://adminos.example")
    private fun notification(title: String = "Bill due in 3 days") =
        Notification(userId = user.id, type = NotificationType.BILL_REMINDER, title = title, body = "HDFC bill of ₹4560.00 is due on 2026-10-19", channel = NotificationChannel.EMAIL)

    @Test
    fun `an email has the title as subject, the body, and links back to the app and to settings`() = runBlocking<Unit> {
        assertEquals(DeliveryResult.Delivered("<msg-1@example.com>"), emailChannel.deliver(notification(), Recipient(user.id, " owner@example.com ")))
        val mail = sender.sent.single()
        assertEquals("owner@example.com", mail.to)
        assertEquals("Bill due in 3 days", mail.subject)
        assertTrue(mail.text.startsWith("HDFC bill of ₹4560.00 is due on 2026-10-19\n"))
        assertTrue("Open AdminOS: https://adminos.example" in mail.text)
        assertTrue("https://adminos.example/settings" in mail.text)
    }

    @Test
    fun `newlines in a title can't become mail headers`() = runBlocking<Unit> {
        emailChannel.deliver(notification("Bill due\r\nBcc: someone@evil.example"), Recipient(user.id, "owner@example.com"))
        assertEquals("Bill due Bcc: someone@evil.example", sender.sent.single().subject)
    }

    @Test
    fun `no address or a bad address is a permanent failure`() = runBlocking<Unit> {
        assertEquals(DeliveryResult.Permanent("The user has no email address"), emailChannel.deliver(notification(), Recipient(user.id, null)))
        assertEquals(DeliveryResult.Permanent("The user has no email address"), emailChannel.deliver(notification(), Recipient(user.id, "  ")))
        assertTrue(emailChannel.deliver(notification(), Recipient(user.id, "not-an-address")) is DeliveryResult.Permanent)
        assertTrue(emailChannel.deliver(notification(title = " "), Recipient(user.id, "owner@example.com")) is DeliveryResult.Permanent)
    }

    @Test
    fun `sender failures map to retryable or permanent`() = runBlocking<Unit> {
        sender.failure = EmailSendException("SMTP send failed: timeout", retryable = true)
        assertEquals(DeliveryResult.Retryable("SMTP send failed: timeout"), emailChannel.deliver(notification(), Recipient(user.id, "owner@example.com")))
        sender.failure = EmailSendException("The SMTP server refused the recipient", retryable = false)
        assertEquals(DeliveryResult.Permanent("The SMTP server refused the recipient"), emailChannel.deliver(notification(), Recipient(user.id, "owner@example.com")))
    }

    @Test
    fun `email follows the email preference`() {
        assertTrue(emailChannel.isWantedBy(NotificationPreferences(userId = user.id, emailNotifications = true)))
        assertTrue(!emailChannel.isWantedBy(NotificationPreferences(userId = user.id, emailNotifications = false)))
    }

    // ── Pending notifications job ──

    private val job = PendingNotificationsJob(repo, dispatcher, clock)

    @Test
    fun `every 5 minutes it sends what is due and leaves the rest`() = runBlocking<Unit> {
        assertEquals(JobName("pending-notifications"), job.name)
        assertEquals(Schedule.Every(Duration.ofMinutes(5)), job.schedule)
        val held = stored(deliverAfter = clock.now.minusSeconds(1))
        val legacy = stored(deliverAfter = null)
        val later = stored(deliverAfter = clock.now.plus(Duration.ofHours(1)))

        assertEquals(mapOf("due" to 2, "sent" to 2, "retrying" to 0, "failed" to 0), job.run().counts)
        assertEquals(NotificationStatus.SENT, repo.findById(held.id)!!.status)
        assertEquals(NotificationStatus.SENT, repo.findById(legacy.id)!!.status)
        assertEquals(NotificationStatus.PENDING, repo.findById(later.id)!!.status)
    }

    @Test
    fun `retries come back after their backoff, and failures are counted`() = runBlocking<Unit> {
        push.next = DeliveryResult.Retryable("down")
        stored()
        stored(channel = NotificationChannel.EMAIL)
        assertEquals(mapOf("due" to 2, "sent" to 0, "retrying" to 1, "failed" to 1), job.run().counts)
        assertEquals(0, job.run().counts["due"], "not due again before the backoff")
        clock.advance(Duration.ofMinutes(5))
        push.next = DeliveryResult.Delivered()
        assertEquals(mapOf("due" to 1, "sent" to 1, "retrying" to 0, "failed" to 0), job.run().counts)
    }

    @Test
    fun `a claimed notification isn't claimed again until its lease ends`() = runBlocking<Unit> {
        val n = stored()
        assertEquals(listOf(n.id), repo.claimDue(clock.now, Duration.ofMinutes(2), 10).map { it.id })
        assertTrue(repo.claimDue(clock.now.plusSeconds(60), Duration.ofMinutes(2), 10).isEmpty())
        assertEquals(listOf(n.id), repo.claimDue(clock.now.plus(Duration.ofMinutes(2)), Duration.ofMinutes(2), 10).map { it.id })
    }

    @Test
    fun `claims are oldest first and limited`() = runBlocking<Unit> {
        val old = stored(createdAt = clock.now.minusSeconds(60))
        stored()
        assertEquals(listOf(old.id), repo.claimDue(clock.now, Duration.ofMinutes(2), 1).map { it.id })
    }
}
