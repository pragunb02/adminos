package dev.adminos.api.infrastructure.push

import java.security.KeyPair
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** RFC 8291 Appendix A: the published example must come out byte for byte. */
class WebPushEncryptionTest {
    private val plaintext = "When I grow up, I want to be a watermelon".toByteArray()
    private val asPrivate = B64.decode("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw")
    private val asPublic = B64.decode("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8")
    private val uaPrivate = B64.decode("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94")
    private val uaPublic = B64.decode("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4")
    private val salt = B64.decode("DGv6ra1nlYgDCS1FRnbzlw")
    private val auth = B64.decode("BTBZMqHH6r4Tts7J_aSIgg")
    private val expected = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"

    private val ephemeral = KeyPair(P256.publicKey(asPublic), P256.privateKey(asPrivate))

    @Test
    fun `the RFC 8291 example encrypts exactly`() {
        assertEquals(expected, B64.encode(WebPushEncryption.encrypt(plaintext, uaPublic, auth, ephemeral, salt)))
    }

    @Test
    fun `a fresh message decrypts with the browser's keys (round trip)`() {
        val body = WebPushEncryption.encrypt(plaintext, uaPublic, auth)
        val msgSalt = body.copyOfRange(0, 16)
        val keyId = body.copyOfRange(21, 86)
        val ecdh = KeyAgreement.getInstance("ECDH").run { init(P256.privateKey(uaPrivate)); doPhase(P256.publicKey(keyId), true); generateSecret() }
        fun hmac(k: ByteArray, d: ByteArray) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(k, "HmacSHA256")); doFinal(d) }
        fun hkdf(s: ByteArray, ikm: ByteArray, info: ByteArray, n: Int) = hmac(hmac(s, ikm), info + 1).copyOf(n)
        val ikm = hkdf(auth, ecdh, "WebPush: info".toByteArray() + 0 + uaPublic + keyId, 32)
        val cek = hkdf(msgSalt, ikm, "Content-Encoding: aes128gcm".toByteArray() + 0, 16)
        val nonce = hkdf(msgSalt, ikm, "Content-Encoding: nonce".toByteArray() + 0, 12)
        val decrypted = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce)); doFinal(body.copyOfRange(86, body.size))
        }
        assertContentEquals(plaintext + 2, decrypted)
    }

    @Test
    fun `bad inputs are refused before any crypto`() {
        assertFailsWith<IllegalArgumentException> { WebPushEncryption.encrypt(ByteArray(3001), uaPublic, auth) }
        assertFailsWith<IllegalArgumentException> { WebPushEncryption.encrypt(plaintext, uaPublic, ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { WebPushEncryption.encrypt(plaintext, uaPublic, auth, salt = ByteArray(8)) }
        assertFailsWith<IllegalArgumentException> { WebPushEncryption.encrypt(plaintext, ByteArray(65).also { it[0] = 4 }, auth) }
    }

    @Test
    fun `raw P-256 keys round-trip and a private key gives its public key`() {
        assertContentEquals(asPublic, P256.encode(P256.publicKey(asPublic)))
        assertContentEquals(asPrivate, P256.encode(P256.privateKey(asPrivate)))
        assertContentEquals(asPublic, P256.encode(P256.publicOf(P256.privateKey(asPrivate))))
        assertContentEquals(uaPublic, P256.encode(P256.publicOf(P256.privateKey(uaPrivate))))
        assertFailsWith<IllegalArgumentException> { P256.privateKey(ByteArray(32)) }
        assertFailsWith<IllegalArgumentException> { P256.privateKey(ByteArray(33)) }
        assertFailsWith<IllegalArgumentException> { P256.publicKey(ByteArray(64)) }
    }

    private operator fun ByteArray.plus(b: Int): ByteArray = this + byteArrayOf(b.toByte())
}
