package dev.adminos.api.domain.notifications

import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Result of a push notification send attempt.
 */
data class PushResult(
    val success: Boolean,
    val messageId: String? = null,
    val error: String? = null
)

/**
 * Firebase Cloud Messaging push notification service.
 *
 * This is a stub implementation — in production, this would integrate with
 * the Firebase Admin SDK to send push notifications via FCM.
 */
class PushService(
    private val deviceRepository: DeviceRepository
) {
    private val logger = LoggerFactory.getLogger(PushService::class.java)

    /**
     * Send a push notification to all of a user's registered devices.
     *
     * @param userId target user
     * @param title notification title
     * @param body notification body
     * @param data optional data payload (entity_type, entity_id, action)
     * @return aggregated push result
     */
    suspend fun sendToUser(
        userId: UUID,
        title: String,
        body: String,
        data: Map<String, String>? = null
    ): PushResult {
        val devices = deviceRepository.findByUserId(userId)
        val tokensToSend = devices.filter { !it.fcmToken.isNullOrBlank() }

        if (tokensToSend.isEmpty()) {
            logger.debug("No FCM tokens found for user {}", userId)
            return PushResult(success = false, error = "no_tokens")
        }

        var lastMessageId: String? = null
        var anySuccess = false

        for (device in tokensToSend) {
            val result = sendToDevice(device.fcmToken!!, title, body, data)
            if (result.success) {
                anySuccess = true
                lastMessageId = result.messageId
            } else if (result.error == "invalid_token") {
                // Remove invalid FCM token
                logger.warn("Removing invalid FCM token for device {}", device.id)
                deviceRepository.removeToken(device.id)
            }
        }

        return PushResult(
            success = anySuccess,
            messageId = lastMessageId
        )
    }

    /**
     * Send a push notification to a specific device via FCM.
     * STUB: In production, this calls Firebase Admin SDK.
     * TODO: Make this `suspend` and wrap FCM HTTP call in withContext(Dispatchers.IO)
     *       when Firebase Admin SDK is integrated.
     */
    internal fun sendToDevice(
        fcmToken: String,
        title: String,
        body: String,
        data: Map<String, String>? = null
    ): PushResult {
        // Stub: simulate FCM send
        // In production: FirebaseMessaging.getInstance().send(message)
        logger.info("FCM stub: sending to token={} title={}", fcmToken.take(10), title)

        // Simulate invalid token detection
        if (fcmToken.startsWith("INVALID_")) {
            return PushResult(success = false, error = "invalid_token")
        }

        return PushResult(
            success = true,
            messageId = "fcm_${System.currentTimeMillis()}"
        )
    }
}
