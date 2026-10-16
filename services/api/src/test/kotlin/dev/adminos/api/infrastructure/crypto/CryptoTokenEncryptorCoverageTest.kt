package dev.adminos.api.infrastructure.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** AES-256-GCM at rest (AOS-312, AOS-608): whole 256-bit key, row-bound ciphertext, tamper detection, legacy reads. */
class CryptoTokenEncryptorCoverageTest {

    private fun newKey() = Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    private val key = newKey()
    private val encryptor = TokenEncryptor(key)
    private val ctx = "user_connections:6f1c0d6e-0000-4000-8000-000000000001:refresh_token"
    private val gmailRefreshToken = "1//0gHdfcRefreshToken-AbCdEfGhIjKlMnOpQrStUvWxYz_0123456789"

    private fun body(s: String) = Base64.getDecoder().decode(s.removePrefix("v2:"))
    private fun v2(b: ByteArray) = "v2:" + Base64.getEncoder().encodeToString(b)

    @Test
    fun `round trip returns the original token, including multi-byte and empty text`() {
        for (t in listOf(gmailRefreshToken, "₹1,250.00 debited — नमस्ते", "")) assertEquals(t, encryptor.decrypt(encryptor.encrypt(t, ctx), ctx))
    }

    @Test
    fun `output is v2 and base64 of a 12-byte IV, the ciphertext and a 16-byte tag, without the plaintext`() {
        val e = encryptor.encrypt("upi:pragun@okhdfcbank", ctx)
        assertTrue(e.startsWith("v2:"))
        assertEquals(12 + "upi:pragun@okhdfcbank".length + 16, body(e).size)
        assertFalse(e.contains("pragun"))
        assertFalse(encryptor.needsReEncryption(e))
    }

    @Test
    fun `every encryption uses a fresh IV`() {
        val a = encryptor.encrypt(gmailRefreshToken, ctx); val b = encryptor.encrypt(gmailRefreshToken, ctx)
        assertNotEquals(a, b)
        assertNotEquals(body(a).copyOf(12).toList(), body(b).copyOf(12).toList())
    }

    @Test
    fun `a ciphertext moved to another row or field does not decrypt`() {
        val e = encryptor.encrypt(gmailRefreshToken, ctx)
        assertFailsWith<AEADBadTagException> { encryptor.decrypt(e, ctx.replace("0001", "0002")) }
        assertFailsWith<AEADBadTagException> { encryptor.decrypt(e, ctx.replace("refresh_token", "access_token")) }
    }

    @Test
    fun `tampering with the IV, ciphertext or tag is detected`() {
        val raw = body(encryptor.encrypt(gmailRefreshToken, ctx))
        for (i in listOf(0, 15, raw.size - 1)) {
            val t = raw.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFailsWith<AEADBadTagException> { encryptor.decrypt(v2(t), ctx) }
        }
        assertFailsWith<AEADBadTagException> { encryptor.decrypt(v2(raw.copyOf(raw.size - 1)), ctx) }
    }

    @Test
    fun `a different key cannot decrypt`() {
        assertFailsWith<AEADBadTagException> { TokenEncryptor(newKey()).decrypt(encryptor.encrypt(gmailRefreshToken, ctx), ctx) }
    }

    @Test
    fun `the whole 256-bit key is used, not a prefix (AOS-608 regression)`() {
        val k = ByteArray(32).also(SecureRandom()::nextBytes)
        val other = k.copyOf().also { it[31] = (it[31].toInt() xor 1).toByte() } // differs only in the last byte
        val a = TokenEncryptor(Base64.getEncoder().encodeToString(k))
        val b = TokenEncryptor(Base64.getEncoder().encodeToString(other))
        assertFailsWith<AEADBadTagException> { b.decrypt(a.encrypt("x", ctx), ctx) }
        // and it is exactly AES-256-GCM over the decoded key with the context as AAD
        val raw = body(a.encrypt("check", ctx))
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, raw.copyOf(12))); updateAAD(ctx.toByteArray())
        }
        assertEquals("check", String(c.doFinal(raw.copyOfRange(12, raw.size))))
    }

    @Test
    fun `the key must be base64 of exactly 32 bytes`() {
        for (bad in listOf("k3y-for-tests-0123456789abcdefghijklmnopqrstuvwxyz", "not base64 !!", Base64.getEncoder().encodeToString(ByteArray(16)),
                           Base64.getEncoder().encodeToString(ByteArray(33)), "")) {
            val e = assertFailsWith<IllegalArgumentException>(bad) { TokenEncryptor(bad) }
            assertTrue(e.message!!.contains("openssl rand -base64 32"))
        }
        TokenEncryptor(" $key\n") // surrounding whitespace from an env file is fine
    }

    @Test
    fun `a value too short to hold an IV is rejected`() {
        assertFailsWith<IllegalArgumentException> { encryptor.decrypt(v2(ByteArray(12)), ctx) }
    }

    @Test
    fun `values written before AOS-608 still decrypt and are flagged for re-encryption`() {
        // Old format: no prefix, key = first 32 UTF-8 bytes of the key text, no AAD.
        val legacyKey = SecretKeySpec(key.toByteArray().copyOf(32), "AES")
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val ct = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, legacyKey, GCMParameterSpec(128, iv)) }
            .doFinal(gmailRefreshToken.toByteArray())
        val old = Base64.getEncoder().encodeToString(iv + ct)
        assertTrue(encryptor.needsReEncryption(old))
        assertEquals(gmailRefreshToken, encryptor.decrypt(old, ctx))
        assertFailsWith<IllegalArgumentException> { encryptor.decrypt(Base64.getEncoder().encodeToString(ByteArray(5)), ctx) }
    }
}
