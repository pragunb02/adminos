package dev.adminos.api.domain.notifications.push

import dev.adminos.api.domain.notifications.Notification
import dev.adminos.api.domain.notifications.NotificationChannel
import dev.adminos.api.domain.notifications.NotificationPreferences
import dev.adminos.api.domain.notifications.NotificationType
import dev.adminos.api.domain.notifications.delivery.DeliveryResult
import dev.adminos.api.domain.notifications.delivery.Recipient
import dev.adminos.api.domain.scheduling.TestClock
import dev.adminos.api.infrastructure.push.B64
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The push channel and turning push on/off per device (AOS-510). */
class WebPushChannelTest {
    private val userId = UUID.randomUUID()
    private val clock = TestClock(Instant.parse("2026-10-16T09:30:00Z"))
    private val repo = InMemoryPushSubscriptionRepository()
    private val answers = mutableMapOf<String, PushSendResult>()
    private val sender = ScriptedPushSender { answers[it.label] ?: PushSendResult.Accepted }
    private val channel = WebPushChannel(repo, sender, clock)
    private val service = PushSubscriptionService(repo, channel, "PUBLIC", clock)

    private fun bill(type: NotificationType = NotificationType.BILL_REMINDER, data: Map<String, String>? = mapOf("entity_type" to "bill", "entity_id" to "b-1")) =
        Notification(userId = userId, type = type, title = "Bill due in 3 days", body = "HDFC bill of ₹4560.00 is due on 2026-10-19",
            data = data, channel = NotificationChannel.PUSH)

    private suspend fun device(label: String) = repo.saved(PushFixtures.subscription(userId, label = label))

    @Test
    fun `push follows the push preference`() {
        assertTrue(channel.isWantedBy(NotificationPreferences(userId = userId, pushNotifications = true)))
        assertFalse(channel.isWantedBy(NotificationPreferences(userId = userId, pushNotifications = false)))
        assertEquals(NotificationChannel.PUSH, channel.channel)
    }

    @Test
    fun `no device with push on is a permanent failure`() = runBlocking<Unit> {
        assertEquals(DeliveryResult.Permanent("No device has push turned on"), channel.deliver(bill(), Recipient(userId, null)))
    }

    @Test
    fun `one accepted device is delivered, and it is marked as recently reached`() = runBlocking<Unit> {
        val phone = device("phone")
        device("laptop")
        answers["laptop"] = PushSendResult.Retryable("503")
        assertEquals(DeliveryResult.Delivered("1 of 2 devices"), channel.deliver(bill(), Recipient(userId, null)))
        assertEquals(clock.now, repo.findByUserId(userId).single { it.id == phone.id }.lastSuccessAt)
    }

    @Test
    fun `gone devices are removed, and with nothing accepted the push fails or retries`() = runBlocking<Unit> {
        device("old")
        answers["old"] = PushSendResult.Gone
        assertEquals(DeliveryResult.Permanent("a device unsubscribed"), channel.deliver(bill(), Recipient(userId, null)))
        assertTrue(repo.findByUserId(userId).isEmpty())

        device("a"); device("b")
        answers["a"] = PushSendResult.Retryable("429"); answers["b"] = PushSendResult.Rejected("403")
        assertEquals(DeliveryResult.Retryable("429"), channel.deliver(bill(), Recipient(userId, null)))
        answers["a"] = PushSendResult.Rejected("403")
        assertEquals(DeliveryResult.Permanent("403"), channel.deliver(bill(), Recipient(userId, null)))
    }

    @Test
    fun `a tap opens the right screen, one notification per thing, anomalies are urgent`() {
        val forBill = WebPushChannel.messageFor(bill())
        assertEquals("/bills", forBill.url)
        assertEquals("bill_reminder-b-1", forBill.tag)
        assertFalse(forBill.urgent)
        val anomaly = WebPushChannel.messageFor(bill(NotificationType.ANOMALY, mapOf("entity_type" to "anomaly", "entity_id" to "an-1")))
        assertEquals("/anomalies/an-1", anomaly.url)
        assertTrue(anomaly.urgent)
        assertEquals("/", WebPushChannel.messageFor(bill(NotificationType.ANOMALY, mapOf("entity_type" to "anomaly"))).url)
        assertEquals("/subscriptions", WebPushChannel.messageFor(bill(data = mapOf("entity_type" to "subscription"))).url)
        val plain = WebPushChannel.messageFor(bill(NotificationType.NUDGE, null))
        assertEquals("/", plain.url)
        assertNull(plain.tag)
        assertEquals(300, WebPushChannel.messageFor(bill().copy(body = "x".repeat(900))).body.length)
    }

    // ── Turning push on and off ──

    @Test
    fun `status says whether push is set up and lists this user's devices`() = runBlocking<Unit> {
        device("phone")
        val status = service.status(userId)
        assertTrue(status.enabled)
        assertEquals("PUBLIC", status.publicKey)
        assertEquals(listOf("phone"), status.devices.map { it.label })
        val off = PushSubscriptionService(repo, null, null).status(userId)
        assertFalse(off.enabled)
        assertNull(off.publicKey)
    }

    @Test
    fun `registering the same device again updates it instead of adding another`() = runBlocking<Unit> {
        val endpoint = PushFixtures.endpoint("same")
        val first = service.register(userId, endpoint, PushFixtures.keys, "  iPhone · Safari  ")
        val again = service.register(userId, endpoint, PushFixtures.keys, "")
        assertEquals(first.id, again.id)
        assertEquals("iPhone · Safari", first.label)
        assertNull(again.label)
        assertEquals(1, repo.findByUserId(userId).size)
    }

    @Test
    fun `a device registered to one user is refused to another and never moves (AOS-627)`() = runBlocking<Unit> {
        val endpoint = PushFixtures.endpoint("shared")
        val mine = service.register(userId, endpoint, PushFixtures.keys, "mine")
        val other = UUID.randomUUID()
        val otherKeys = PushKeys(PushFixtures.UA_PUBLIC, B64.encode(ByteArray(16) { 7 }))

        assertFailsWith<PushEndpointTakenException> { service.register(other, endpoint, otherKeys, "theirs") }

        val kept = repo.findByUserId(userId).single()
        assertEquals(mine.id, kept.id)
        assertEquals("mine", kept.label)
        assertEquals(PushFixtures.keys, kept.keys, "the attacker's keys must not replace the owner's")
        assertTrue(repo.findByUserId(other).isEmpty())
    }

    @Test
    fun `a device is free for another user once its owner turns it off`() = runBlocking<Unit> {
        val endpoint = PushFixtures.endpoint("handed-on")
        service.register(userId, endpoint, PushFixtures.keys, null)
        assertTrue(service.unregister(userId, endpoint))
        val other = UUID.randomUUID()
        assertEquals(other, service.register(other, endpoint, PushFixtures.keys, null).userId)
    }

    @Test
    fun `beyond 10 devices the oldest is dropped`() = runBlocking<Unit> {
        repeat(11) { i ->
            clock.advance(java.time.Duration.ofMinutes(1))
            service.register(userId, PushFixtures.endpoint("d$i"), PushFixtures.keys, "d$i")
        }
        val labels = repo.findByUserId(userId).map { it.label }
        assertEquals(10, labels.size)
        assertFalse("d0" in labels)
    }

    @Test
    fun `turning off only works on your own device`() = runBlocking<Unit> {
        val endpoint = PushFixtures.endpoint("mine")
        service.register(userId, endpoint, PushFixtures.keys, null)
        assertFalse(service.unregister(UUID.randomUUID(), endpoint))
        assertTrue(service.unregister(userId, endpoint))
        assertFalse(service.unregister(userId, endpoint))
    }

    @Test
    fun `a test push goes to every device now and reports the outcome`() = runBlocking<Unit> {
        assertEquals(PushTestResult(0, false, "No device has push turned on"), service.sendTest(userId))
        device("phone")
        assertEquals(PushTestResult(1, true, "1 of 1 devices"), service.sendTest(userId))
        assertEquals("AdminOS test", sender.sent.last().second.title)
        assertEquals("/settings", sender.sent.last().second.url)
        answers["phone"] = PushSendResult.Retryable("503")
        assertEquals(PushTestResult(1, false, "503"), service.sendTest(userId))
        answers["phone"] = PushSendResult.Rejected("403")
        assertEquals(PushTestResult(1, false, "403"), service.sendTest(userId))
    }

    @Test
    fun `without server keys, turning push on or testing it is refused clearly`() = runBlocking<Unit> {
        val off = PushSubscriptionService(repo, null, null)
        assertFailsWith<PushNotConfiguredException> { off.register(userId, PushFixtures.endpoint(), PushFixtures.keys, null) }
        assertFailsWith<PushNotConfiguredException> { off.sendTest(userId) }
    }

    @Test
    fun `the in-memory repository ignores unknown ids`() = runBlocking<Unit> {
        repo.deleteById(UUID.randomUUID())
        repo.markSucceeded(UUID.randomUUID(), clock.now)
        assertTrue(repo.findByUserId(userId).isEmpty())
    }
}
