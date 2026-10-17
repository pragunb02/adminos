package dev.adminos.api.domain.ingestion.connection

import java.time.Instant

/*
 * What the Gmail push domain needs from Google (AOS-405, D-63), as interfaces the domain owns.
 * Infrastructure implements them (HttpGmailPushApi, GoogleOAuthClient); tests use fakes.
 * Google's request/response models never cross these interfaces.
 */

/** Gmail's push registration calls: `users.watch` and `users.stop` for the signed-in mailbox. */
interface GmailPushApi {
    /** Asks Gmail to publish this mailbox's changes to [topic] (`projects/<p>/topics/<t>`). */
    suspend fun watch(accessToken: String, topic: String): WatchRegistration

    /** Asks Gmail to stop publishing this mailbox's changes. */
    suspend fun stop(accessToken: String)
}

/** What Gmail answered to `users.watch`: its mailbox position and when publishing stops. */
data class WatchRegistration(val historyId: String, val expiresAt: Instant) {
    init {
        require(historyId.matches(Regex("[0-9]{1,20}"))) { "A Gmail history ID is a number" }
    }
}

/** Why a Gmail push call failed, as statuses only: Google's response bodies are never kept (D-59). */
sealed class GmailPushException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    /** 401: the access token was not accepted (expired early, or revoked). */
    class Unauthorized : GmailPushException("Gmail did not accept the access token")

    /** Any other non-success status, e.g. 403 when the topic doesn't let Gmail publish. */
    class Rejected(val status: Int) : GmailPushException("Gmail answered HTTP $status")

    /** No answer (network, timeout) or an answer we could not read. */
    class Unavailable(cause: Throwable) : GmailPushException("Gmail is unreachable", cause)
}

/** Exchanges a Google refresh token for a new access token. */
fun interface GmailTokenRefresher {
    suspend fun refresh(refreshToken: String): RefreshedAccessToken
}

/** A new access token in plain text (encrypted before it is stored) and when it expires. */
data class RefreshedAccessToken(val accessToken: String, val expiresAt: Instant?) {
    init {
        require(accessToken.isNotBlank()) { "A refreshed access token can't be blank" }
    }

    override fun toString() = "RefreshedAccessToken(expiresAt=$expiresAt)" // never print tokens
}

/** Why no usable access token could be had for a connection. */
sealed class GmailAccessException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    /** Google refused the refresh token (`invalid_grant`): the user must connect Gmail again. */
    class Revoked : GmailAccessException(REVOKED_REASON)

    /** The connection has no refresh token, or a stored token can't be decrypted with our key. */
    class Unreadable(cause: Throwable? = null) : GmailAccessException("The stored Gmail tokens can't be used", cause)

    /** Google's token endpoint couldn't be reached or answered with an error other than a refusal. */
    class Unavailable(cause: Throwable? = null) : GmailAccessException("Google's token service is unreachable", cause)

    companion object {
        /** Same words as the worker's revocation reason, so the console shows one message. */
        const val REVOKED_REASON = "gmail access revoked: reconnect Gmail"
    }
}
