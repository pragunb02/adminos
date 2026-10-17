package dev.adminos.api.domain.notifications.push

import java.net.URI
import java.net.URISyntaxException
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Where a browser's push service accepts messages for one device (AOS-510). The server POSTs to
 * this URL, so only the known push services are allowed: a forged subscription can't make the
 * server call anything else (SSRF).
 */
@JvmInline
value class PushEndpoint(val url: String) {
    init {
        val uri = try { URI(url) } catch (e: URISyntaxException) { throw IllegalArgumentException("endpoint: not a URL") }
        require(uri.scheme == "https") { "endpoint: must be https" }
        require(uri.port == -1 || uri.port == 443) { "endpoint: must use the default https port" }
        require(uri.rawUserInfo == null) { "endpoint: must not contain credentials" }
        val host = uri.host?.lowercase() ?: throw IllegalArgumentException("endpoint: has no host")
        require(PUSH_SERVICES.any { host == it || host.endsWith(".$it") }) {
            "endpoint: not a known push service (Apple, Google, Mozilla or Microsoft)"
        }
        require(url.length <= 2048) { "endpoint: too long" }
    }

    /** The push service origin, the VAPID audience (RFC 8292 §2). */
    val origin: String get() = URI(url).let { "${it.scheme}://${it.host}" }

    override fun toString() = origin // never log the full endpoint: it identifies a device

    companion object {
        /** Push services used by Safari/iOS, Chrome/Android, Firefox and Edge. */
        val PUSH_SERVICES = listOf("push.apple.com", "fcm.googleapis.com", "push.services.mozilla.com", "notify.windows.com")
    }
}

/** A browser's encryption keys for push (RFC 8291): `p256dh` (65-byte P-256 point) and `auth` (16 bytes). */
data class PushKeys(val p256dh: String, val auth: String) {
    init {
        val point = decode(p256dh, "p256dh")
        require(point.size == 65 && point[0] == 0x04.toByte()) { "keys.p256dh: must be a 65-byte uncompressed P-256 key" }
        require(decode(auth, "auth").size == 16) { "keys.auth: must be 16 bytes" }
    }

    fun p256dhBytes(): ByteArray = decode(p256dh, "p256dh")
    fun authBytes(): ByteArray = decode(auth, "auth")

    /** Never print key material. */
    override fun toString() = "PushKeys(…)"

    private companion object {
        fun decode(value: String, field: String): ByteArray = try {
            Base64.getUrlDecoder().decode(value.trim().trimEnd('='))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("keys.$field: not URL-safe base64")
        }
    }
}

/** One device that has push turned on. */
data class PushSubscription(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val endpoint: PushEndpoint,
    val keys: PushKeys,
    /** e.g. "iPhone · Safari" — shown in Settings so the user knows which device this is. */
    val label: String? = null,
    val createdAt: Instant = Instant.now(),
    val lastSuccessAt: Instant? = null
) {
    init {
        require(label == null || label.length <= 100) { "label: at most 100 characters" }
    }
}

/** What saving a subscription did (AOS-627). */
sealed interface PushSaveResult {
    /** Stored: a new device, or the same user's device updated (keys rotate, label changes), keeping its id. */
    data class Saved(val subscription: PushSubscription) : PushSaveResult

    /** The endpoint is registered to another user: nothing was changed. A device never moves between users. */
    data object OwnedByAnotherUser : PushSaveResult
}

interface PushSubscriptionRepository {
    /**
     * Saves [subscription], one row per endpoint. The same user's existing row is updated in place; a row of another
     * user is left untouched (atomically, so two registrations can't race a device across users).
     */
    suspend fun save(subscription: PushSubscription): PushSaveResult
    suspend fun findByUserId(userId: UUID): List<PushSubscription>
    /** @return true if a subscription of [userId] with [endpoint] was removed */
    suspend fun delete(userId: UUID, endpoint: PushEndpoint): Boolean
    /** Removes a subscription the push service says is gone (404/410), whoever owns it. */
    suspend fun deleteById(id: UUID)
    suspend fun markSucceeded(id: UUID, at: Instant)
}
