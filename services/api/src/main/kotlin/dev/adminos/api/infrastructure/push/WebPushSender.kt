package dev.adminos.api.infrastructure.push

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.adminos.api.config.VapidConfig
import dev.adminos.api.domain.notifications.push.PushEndpoint
import dev.adminos.api.domain.notifications.push.PushMessage
import dev.adminos.api.domain.notifications.push.PushSendResult
import dev.adminos.api.domain.notifications.push.PushSender
import dev.adminos.api.domain.notifications.push.PushSubscription
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Clock
import java.time.Duration
import java.util.Date

/**
 * Web Push over HTTP (AOS-510): RFC 8291 encryption, RFC 8292 VAPID authentication, RFC 8030
 * delivery. Works with Apple (iPhone Home Screen apps), Google, Mozilla and Microsoft push services.
 */
class WebPushSender(
    private val vapid: VapidConfig,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build(),
    private val clock: Clock = Clock.systemUTC(),
    /** Test seam: where a (validated) endpoint is actually sent; production sends to the endpoint itself. */
    private val resolve: (PushEndpoint) -> URI = { URI(it.url) }
) : PushSender {

    override suspend fun send(subscription: PushSubscription, message: PushMessage): PushSendResult {
        val body = WebPushEncryption.encrypt(payload(message), subscription.keys.p256dhBytes(), subscription.keys.authBytes())
        val request = HttpRequest.newBuilder(resolve(subscription.endpoint))
            .timeout(Duration.ofSeconds(10))
            .header("TTL", message.ttlSeconds.toString())
            .header("Urgency", if (message.urgent) "high" else "normal")
            .header("Content-Encoding", "aes128gcm")
            .header("Content-Type", "application/octet-stream")
            .header("Authorization", "vapid t=${token(subscription.endpoint.origin)}, k=${vapid.publicKey}")
            .apply { message.tag?.let { header("Topic", topic(it)) } }
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val response = try {
            http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await()
        } catch (e: HttpTimeoutException) {
            return PushSendResult.Retryable("The push service timed out")
        } catch (e: IOException) {
            return PushSendResult.Retryable("Couldn't reach the push service: ${e::class.simpleName}")
        }
        return classify(response.statusCode())
    }

    /** The JSON the service worker shows (apps/web/public/sw.js). */
    internal fun payload(message: PushMessage): ByteArray = buildJsonObject {
        put("title", message.title)
        put("body", message.body)
        put("url", message.url)
        message.tag?.let { put("tag", it) }
    }.toString().toByteArray(Charsets.UTF_8)

    /** RFC 8292 §2: ES256 JWT for the push service's origin, valid 12 h (the maximum is 24 h). */
    internal fun token(audience: String): String {
        val now = clock.instant()
        val claims = JWTClaimsSet.Builder().audience(audience).expirationTime(Date.from(now.plus(Duration.ofHours(12)))).subject(vapid.subject).build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).build(), claims)
            .apply { sign(ECDSASigner(P256.privateKey(B64.decode(vapid.privateKey)))) }
            .serialize()
    }

    internal companion object {
        fun classify(status: Int): PushSendResult = when (status) {
            200, 201, 202 -> PushSendResult.Accepted
            404, 410 -> PushSendResult.Gone
            429 -> PushSendResult.Retryable("The push service is rate limiting (429)")
            in 500..599 -> PushSendResult.Retryable("The push service failed ($status)")
            413 -> PushSendResult.Rejected("The message is too large (413)")
            400, 401, 403 -> PushSendResult.Rejected("The push service refused our credentials or request ($status)")
            else -> PushSendResult.Rejected("Unexpected answer from the push service ($status)")
        }

        /** RFC 8030 §5.4: a Topic is at most 32 URL-safe base64 characters. */
        fun topic(tag: String): String = tag.replace(Regex("[^A-Za-z0-9_-]"), "-").take(32)
    }
}
