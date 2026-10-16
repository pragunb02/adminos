package dev.adminos.api.infrastructure.push

import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Message encryption for Web Push, RFC 8291 (`aes128gcm`, RFC 8188), with JDK crypto only (AOS-510).
 * Verified against the RFC's Appendix A test vector. One record, so the payload must fit in
 * [MAX_PLAINTEXT] bytes.
 */
internal object WebPushEncryption {
    /** Record size: one 4096-byte record, as push services accept. */
    private const val RECORD_SIZE = 4096
    /** 4096 − 16 (GCM tag) − 1 (padding delimiter) − 86 (header) leaves room; keep a safe margin. */
    const val MAX_PLAINTEXT = 3000

    private val random = SecureRandom()

    /**
     * @param uaPublic the browser's `p256dh` key (65 bytes)
     * @param authSecret the browser's `auth` secret (16 bytes)
     * @param ephemeral and [salt] are fresh per message; only tests pass them
     * @return the request body: header (salt ‖ rs ‖ idlen ‖ keyid) ‖ ciphertext
     */
    fun encrypt(
        plaintext: ByteArray,
        uaPublic: ByteArray,
        authSecret: ByteArray,
        ephemeral: KeyPair = P256.generate(),
        salt: ByteArray = ByteArray(16).also(random::nextBytes)
    ): ByteArray {
        require(plaintext.size <= MAX_PLAINTEXT) { "A push message is at most $MAX_PLAINTEXT bytes, was ${plaintext.size}" }
        require(authSecret.size == 16) { "The auth secret is 16 bytes" }
        require(salt.size == 16) { "The salt is 16 bytes" }
        val uaKey = P256.publicKey(uaPublic)
        val asPublic = P256.encode(ephemeral.public as ECPublicKey)

        val ecdhSecret = KeyAgreement.getInstance("ECDH").run { init(ephemeral.private); doPhase(uaKey, true); generateSecret() }
        // RFC 8291 §3.4: IKM = HKDF(auth_secret, ecdh_secret, "WebPush: info" ‖ 0x00 ‖ ua_public ‖ as_public, 32)
        val ikm = hkdf(salt = authSecret, ikm = ecdhSecret, info = "WebPush: info".toByteArray() + 0 + uaPublic + asPublic, length = 32)
        // RFC 8188 §2.2: CEK and NONCE from the salt
        val cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm".toByteArray() + 0, 16)
        val nonce = hkdf(salt, ikm, "Content-Encoding: nonce".toByteArray() + 0, 12)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        }
        val ciphertext = cipher.doFinal(plaintext + 0x02) // 0x02: last (and only) record, no padding

        return ByteBuffer.allocate(16 + 4 + 1 + asPublic.size + ciphertext.size)
            .put(salt).putInt(RECORD_SIZE).put(asPublic.size.toByte()).put(asPublic).put(ciphertext)
            .array()
    }

    /** HKDF-SHA256 (RFC 5869), single block (length ≤ 32). */
    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        return hmac(prk, info + 1).copyOf(length)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    private operator fun ByteArray.plus(b: Int): ByteArray = this + byteArrayOf(b.toByte())
}
