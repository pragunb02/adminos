package dev.adminos.api.domain.ingestion.connection

import dev.adminos.api.infrastructure.crypto.TokenEncryptor
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** AOS-405: a fresh random key per test, so no key is ever written down. */
internal fun randomTokenEncryptor() = TokenEncryptor(Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes)))

/** A random, token-shaped value generated at run time (never a literal token in the repo). */
internal fun runtimeToken(prefix: String = "ya29.") = prefix + UUID.randomUUID().toString().replace("-", "")

/** Gmail's users.watch / users.stop as a test double: records the access tokens it was called with. */
internal class FakeGmailPushApi(
    var historyId: String = "9876543",
    var expiresAt: Instant = Instant.parse("2026-10-24T06:00:00Z")
) : GmailPushApi {
    /** Thrown by the next watch calls, in order; empty = succeed. */
    val watchFailures = ArrayDeque<Exception>()
    var stopFailure: Exception? = null
    val watchTokens = CopyOnWriteArrayList<String>()
    val watchTopics = CopyOnWriteArrayList<String>()
    val stopTokens = CopyOnWriteArrayList<String>()

    override suspend fun watch(accessToken: String, topic: String): WatchRegistration {
        watchTokens += accessToken
        watchTopics += topic
        watchFailures.removeFirstOrNull()?.let { throw it }
        return WatchRegistration(historyId, expiresAt)
    }

    override suspend fun stop(accessToken: String) {
        stopTokens += accessToken
        stopFailure?.let { throw it }
    }
}

/** Google's token refresh as a test double: hands out a new runtime token per call, or fails. */
internal class FakeTokenRefresher(private val expiresAt: () -> Instant?) : GmailTokenRefresher {
    var failure: Exception? = null
    val refreshTokens = CopyOnWriteArrayList<String>()
    val issued = CopyOnWriteArrayList<String>()

    override suspend fun refresh(refreshToken: String): RefreshedAccessToken {
        refreshTokens += refreshToken
        failure?.let { throw it }
        return RefreshedAccessToken(runtimeToken(), expiresAt()).also { issued += it.accessToken }
    }
}
