package dev.adminos.api.domain.notifications.push

/** What a push shows: kept small (push payloads are limited to a few KB). */
data class PushMessage(
    val title: String,
    val body: String,
    /** Path inside the app to open on tap, e.g. "/bills". Always app-relative. */
    val url: String = "/",
    /** Same tag replaces an earlier notification instead of stacking (e.g. one per bill). */
    val tag: String? = null,
    val urgent: Boolean = false,
    /** How long the push service keeps it for an offline device. */
    val ttlSeconds: Long = 24 * 60 * 60
) {
    init {
        require(title.isNotBlank()) { "A push needs a title" }
        require(url.startsWith("/") && !url.startsWith("//")) { "url: must be a path inside the app" }
        require(ttlSeconds in 0..(28L * 24 * 60 * 60)) { "ttl: 0 to 28 days" }
    }
}

/** How a push service answered for one device. */
sealed interface PushSendResult {
    data object Accepted : PushSendResult
    /** 404/410: the device unsubscribed or the subscription expired — remove it. */
    data object Gone : PushSendResult
    /** 429/5xx/network: try again later. */
    data class Retryable(val reason: String) : PushSendResult
    /** Other 4xx: this message or our credentials were refused; retrying won't help. */
    data class Rejected(val reason: String) : PushSendResult
}

/** Sends one push to one device. Implementations: Web Push over HTTP (production) and fakes in tests. */
interface PushSender {
    suspend fun send(subscription: PushSubscription, message: PushMessage): PushSendResult
}
