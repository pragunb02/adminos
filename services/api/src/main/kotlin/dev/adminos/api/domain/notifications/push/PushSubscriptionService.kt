package dev.adminos.api.domain.notifications.push

import dev.adminos.api.domain.notifications.delivery.DeliveryResult
import java.time.Clock
import java.util.UUID

/** What Settings needs to offer push (AOS-510). */
data class PushStatus(val enabled: Boolean, val publicKey: String?, val devices: List<PushSubscription>)

/** Result of "Send a test": how many of the user's devices the push services accepted. */
data class PushTestResult(val devices: Int, val delivered: Boolean, val detail: String)

/** Thrown when push is used while the server has no VAPID keys. */
class PushNotConfiguredException : RuntimeException("Push notifications aren't set up on this server")

/**
 * Turning push on and off per device. [channel] is null when the server has no VAPID keys; then
 * registering is refused (with a clear error) rather than storing subscriptions nothing will use.
 */
class PushSubscriptionService(
    private val subscriptions: PushSubscriptionRepository,
    private val channel: WebPushChannel?,
    private val publicKey: String?,
    private val clock: Clock = Clock.systemUTC()
) {
    suspend fun status(userId: UUID) = PushStatus(enabled = channel != null, publicKey = publicKey, devices = subscriptions.findByUserId(userId))

    /**
     * Registers this device for [userId]. Re-registering the same endpoint updates it (keys rotate);
     * beyond [MAX_DEVICES] the oldest device is dropped, so reinstalling never locks anyone out.
     */
    suspend fun register(userId: UUID, endpoint: PushEndpoint, keys: PushKeys, label: String?): PushSubscription {
        if (channel == null) throw PushNotConfiguredException()
        val saved = subscriptions.upsert(PushSubscription(userId = userId, endpoint = endpoint, keys = keys,
            label = label?.trim()?.take(100)?.ifEmpty { null }, createdAt = clock.instant()))
        subscriptions.findByUserId(userId).sortedByDescending { it.createdAt }.drop(MAX_DEVICES).forEach { subscriptions.deleteById(it.id) }
        return saved
    }

    /** @return false if this user had no such device */
    suspend fun unregister(userId: UUID, endpoint: PushEndpoint): Boolean = subscriptions.delete(userId, endpoint)

    /** Sends a test push to all of the user's devices now, ignoring quiet hours. */
    suspend fun sendTest(userId: UUID): PushTestResult {
        val push = channel ?: throw PushNotConfiguredException()
        val devices = subscriptions.findByUserId(userId)
        if (devices.isEmpty()) return PushTestResult(0, delivered = false, detail = "No device has push turned on")
        val result = push.send(devices, PushMessage(title = "AdminOS test", body = "Push notifications work on this device.", url = "/settings", tag = "push-test"))
        return when (result) {
            is DeliveryResult.Delivered -> PushTestResult(devices.size, delivered = true, detail = result.reference.orEmpty())
            is DeliveryResult.Retryable -> PushTestResult(devices.size, delivered = false, detail = result.reason)
            is DeliveryResult.Permanent -> PushTestResult(devices.size, delivered = false, detail = result.reason)
        }
    }

    companion object {
        const val MAX_DEVICES = 10
    }
}
