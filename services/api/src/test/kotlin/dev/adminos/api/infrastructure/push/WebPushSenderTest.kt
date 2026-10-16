package dev.adminos.api.infrastructure.push

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import dev.adminos.api.config.VapidConfig
import dev.adminos.api.domain.notifications.push.PushFixtures
import dev.adminos.api.domain.notifications.push.PushMessage
import dev.adminos.api.domain.notifications.push.PushSendResult
import dev.adminos.api.domain.scheduling.TestClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Web Push over HTTP against a local stand-in push service (AOS-510). */
class WebPushSenderTest {
    private val pair = P256.generate()
    private val vapid = VapidConfig(
        publicKey = B64.encode(P256.encode(pair.public as ECPublicKey)),
        privateKey = B64.encode(P256.encode(pair.private as ECPrivateKey)),
        subject = "mailto:pragun@example.com"
    )
    private val clock = TestClock(Instant.parse("2026-10-16T09:30:00Z"))
    private val subscription = PushFixtures.subscription(UUID.randomUUID(), device = "abc")

    private var status = 201
    private var delayMs = 0L
    private val received = mutableListOf<Pair<Map<String, String>, ByteArray>>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            Thread.sleep(delayMs)
            received += ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") } to ex.requestBody.readAllBytes()
            ex.sendResponseHeaders(status, -1)
            ex.close()
        }
        start()
    }

    private fun sender(port: Int = server.address.port, timeout: Duration = Duration.ofSeconds(10)) = WebPushSender(
        vapid, HttpClient.newBuilder().connectTimeout(timeout).build(), clock, resolve = { URI("http://127.0.0.1:$port/push/${it.url.substringAfterLast('/')}") }
    )

    @AfterTest fun stop() = server.stop(0)

    @Test
    fun `a push is an encrypted POST with TTL, urgency, topic and a VAPID signature`() = runBlocking<Unit> {
        val message = PushMessage("Bill due in 3 days", "HDFC bill of ₹4560.00", url = "/bills", tag = "bill_reminder-b-1", urgent = true, ttlSeconds = 3600)
        assertEquals(PushSendResult.Accepted, sender().send(subscription, message))

        val (headers, body) = received.single()
        assertEquals("3600", headers["ttl"])
        assertEquals("high", headers["urgency"])
        assertEquals("aes128gcm", headers["content-encoding"])
        assertEquals("bill_reminder-b-1", headers["topic"])

        val auth = headers.getValue("authorization")
        assertTrue(auth.startsWith("vapid t="))
        assertTrue(auth.endsWith(", k=${vapid.publicKey}"))
        val jwt = SignedJWT.parse(auth.removePrefix("vapid t=").substringBefore(","))
        assertTrue(jwt.verify(ECDSAVerifier(pair.public as ECPublicKey)))
        assertEquals(listOf("https://web.push.apple.com"), jwt.jwtClaimsSet.audience)
        assertEquals("mailto:pragun@example.com", jwt.jwtClaimsSet.subject)
        assertEquals(clock.now.plus(Duration.ofHours(12)), jwt.jwtClaimsSet.expirationTime.toInstant())

        val json = Json.parseToJsonElement(decrypt(body)).jsonObject
        assertEquals("Bill due in 3 days", json["title"]!!.jsonPrimitive.content)
        assertEquals("HDFC bill of ₹4560.00", json["body"]!!.jsonPrimitive.content)
        assertEquals("/bills", json["url"]!!.jsonPrimitive.content)
        assertEquals("bill_reminder-b-1", json["tag"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a normal push without a tag has normal urgency and no topic`() = runBlocking<Unit> {
        sender().send(subscription, PushMessage("Hi", "there"))
        val headers = received.single().first
        assertEquals("normal", headers["urgency"])
        assertEquals(null, headers["topic"])
        assertEquals("86400", headers["ttl"])
    }

    @Test
    fun `push service answers map to accepted, gone, retry or rejected`() = runBlocking<Unit> {
        mapOf(
            200 to PushSendResult.Accepted, 202 to PushSendResult.Accepted,
            404 to PushSendResult.Gone, 410 to PushSendResult.Gone,
        ).forEach { (code, expected) -> status = code; assertEquals(expected, sender().send(subscription, PushMessage("t", "b")), "$code") }
        listOf(429, 500, 503).forEach { code -> status = code; assertTrue(sender().send(subscription, PushMessage("t", "b")) is PushSendResult.Retryable, "$code") }
        listOf(400, 401, 403, 413, 418).forEach { code -> status = code; assertTrue(sender().send(subscription, PushMessage("t", "b")) is PushSendResult.Rejected, "$code") }
    }

    @Test
    fun `an unreachable or slow push service is retryable`() = runBlocking<Unit> {
        val closed = ServerSocket(0).use { it.localPort }
        assertEquals(PushSendResult.Retryable("Couldn't reach the push service: ConnectException"), sender(port = closed).send(subscription, PushMessage("t", "b")))
        delayMs = 11_000
        assertEquals(PushSendResult.Retryable("The push service timed out"), sender().send(subscription, PushMessage("t", "b")))
    }

    @Test
    fun `topics are cut to 32 URL-safe characters`() {
        assertEquals("bill_reminder-1d2c-a-b-c-d-9f0e1", WebPushSender.topic("bill_reminder-1d2c/a b:c.d-9f0e1aaaaaa"))
    }

    /** What the browser does: decrypt with its private key and auth secret (RFC 8291). */
    private fun decrypt(body: ByteArray): String {
        val salt = body.copyOfRange(0, 16)
        val keyId = body.copyOfRange(21, 86)
        val ecdh = KeyAgreement.getInstance("ECDH").run { init(PushFixtures.uaPrivateKey); doPhase(P256.publicKey(keyId), true); generateSecret() }
        fun hmac(k: ByteArray, d: ByteArray) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(k, "HmacSHA256")); doFinal(d) }
        fun hkdf(s: ByteArray, ikm: ByteArray, info: ByteArray, n: Int) = hmac(hmac(s, ikm), info + byteArrayOf(1)).copyOf(n)
        val ua = B64.decode(PushFixtures.UA_PUBLIC)
        val ikm = hkdf(B64.decode(PushFixtures.AUTH), ecdh, "WebPush: info".toByteArray() + byteArrayOf(0) + ua + keyId, 32)
        val cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm".toByteArray() + byteArrayOf(0), 16)
        val nonce = hkdf(salt, ikm, "Content-Encoding: nonce".toByteArray() + byteArrayOf(0), 12)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce)); doFinal(body.copyOfRange(86, body.size)) }
        return String(plain.copyOf(plain.size - 1), Charsets.UTF_8)
    }
}
