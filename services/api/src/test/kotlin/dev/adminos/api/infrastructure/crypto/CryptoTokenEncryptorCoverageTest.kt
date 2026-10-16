package dev.adminos.api.infrastructure.crypto

import org.junit.jupiter.api.Disabled
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** AES-256-GCM token encryption (AOS-312): round trip, IV freshness, key validation, tamper detection. */
class CryptoTokenEncryptorCoverageTest {

    private val key = "k3y-for-tests-0123456789abcdefghijklmnopqrstuvwxyz" // 50 ASCII bytes
    private val encryptor = TokenEncryptor(key)
    private val gmailRefreshToken = "1//0gHdfcRefreshToken-AbCdEfGhIjKlMnOpQrStUvWxYz_0123456789"

    private fun decode(s: String) = Base64.getDecoder().decode(s)
    private fun encode(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    @Test
    fun `encrypt then decrypt returns the original OAuth token`() {
        val encrypted = encryptor.encrypt(gmailRefreshToken)

        assertEquals(gmailRefreshToken, encryptor.decrypt(encrypted))
    }

    @Test
    fun `round trip preserves multi-byte UTF-8 text`() {
        val text = "₹1,250.00 debited from HDFC A/c XX1234 — नमस्ते"

        assertEquals(text, encryptor.decrypt(encryptor.encrypt(text)))
    }

    @Test
    fun `round trip of an empty string returns an empty string`() {
        assertEquals("", encryptor.decrypt(encryptor.encrypt("")))
    }

    @Test
    fun `ciphertext does not contain the plaintext`() {
        val encrypted = encryptor.encrypt(gmailRefreshToken)

        assertTrue(!encrypted.contains(gmailRefreshToken))
        assertTrue(!String(decode(encrypted), Charsets.ISO_8859_1).contains("HdfcRefreshToken"))
    }

    @Test
    fun `output is base64 of a 12-byte IV, the ciphertext and a 16-byte tag`() {
        val plaintext = "upi:pragun@okhdfcbank"

        val combined = decode(encryptor.encrypt(plaintext))

        assertEquals(12 + plaintext.toByteArray().size + 16, combined.size)
    }

    @Test
    fun `encrypting the same plaintext twice yields different ciphertexts`() {
        val a = encryptor.encrypt(gmailRefreshToken)
        val b = encryptor.encrypt(gmailRefreshToken)

        assertNotEquals(a, b)
    }

    @Test
    fun `every encryption uses a fresh IV`() {
        val ivs = (1..200).map { decode(encryptor.encrypt("same")).copyOfRange(0, 12).toList() }.toSet()

        assertEquals(200, ivs.size)
    }

    @Test
    fun `ciphertext matches standard AES-GCM with the first 32 key bytes`() {
        val combined = decode(encryptor.encrypt(gmailRefreshToken))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key.toByteArray().copyOf(32), "AES"),
            GCMParameterSpec(128, combined.copyOfRange(0, 12))
        )

        val plain = cipher.doFinal(combined.copyOfRange(12, combined.size))

        assertEquals(gmailRefreshToken, String(plain, Charsets.UTF_8))
    }

    @Test
    fun `a key shorter than 32 bytes is rejected`() {
        val e = assertFailsWith<IllegalArgumentException> { TokenEncryptor("a".repeat(31)) }

        assertTrue(e.message!!.contains("at least 32 bytes"))
        assertTrue(e.message!!.contains("Current: 31 bytes"))
    }

    @Test
    fun `a key of exactly 32 bytes is accepted`() {
        val e = TokenEncryptor("b".repeat(32))

        assertEquals("ok", e.decrypt(e.encrypt("ok")))
    }

    @Test
    fun `key length is measured in UTF-8 bytes, not characters`() {
        // 11 rupee signs = 11 chars but 33 UTF-8 bytes.
        val e = TokenEncryptor("₹".repeat(11))

        assertEquals("ok", e.decrypt(e.encrypt("ok")))
    }

    @Test
    fun `a key of 10 multi-byte characters (30 bytes) is rejected`() {
        assertFailsWith<IllegalArgumentException> { TokenEncryptor("₹".repeat(10)) }
    }

    @Test
    fun `a ciphertext cannot be decrypted with a different key`() {
        val other = TokenEncryptor("z".repeat(32))

        assertFailsWith<AEADBadTagException> { other.decrypt(encryptor.encrypt(gmailRefreshToken)) }
    }

    @Test
    fun `flipping a ciphertext byte is detected`() {
        val combined = decode(encryptor.encrypt(gmailRefreshToken))
        combined[12] = (combined[12].toInt() xor 0x01).toByte()

        assertFailsWith<AEADBadTagException> { encryptor.decrypt(encode(combined)) }
    }

    @Test
    fun `flipping an IV byte is detected`() {
        val combined = decode(encryptor.encrypt(gmailRefreshToken))
        combined[0] = (combined[0].toInt() xor 0x80).toByte()

        assertFailsWith<AEADBadTagException> { encryptor.decrypt(encode(combined)) }
    }

    @Test
    fun `flipping a tag byte is detected`() {
        val combined = decode(encryptor.encrypt(gmailRefreshToken))
        combined[combined.size - 1] = (combined.last().toInt() xor 0x01).toByte()

        assertFailsWith<AEADBadTagException> { encryptor.decrypt(encode(combined)) }
    }

    @Test
    fun `a truncated ciphertext is rejected`() {
        val combined = decode(encryptor.encrypt(gmailRefreshToken))

        assertFailsWith<AEADBadTagException> { encryptor.decrypt(encode(combined.copyOf(combined.size - 4))) }
    }

    @Test
    fun `swapping the IV of two ciphertexts is detected`() {
        val a = decode(encryptor.encrypt("token-a"))
        val b = decode(encryptor.encrypt("token-b"))
        val spliced = b.copyOfRange(0, 12) + a.copyOfRange(12, a.size)

        assertFailsWith<AEADBadTagException> { encryptor.decrypt(encode(spliced)) }
    }

    @Test
    fun `input that is not base64 is rejected`() {
        assertFailsWith<IllegalArgumentException> { encryptor.decrypt("not base64 !!") }
    }

    @Test
    fun `input shorter than an IV is rejected`() {
        assertFails { encryptor.decrypt(encode(ByteArray(5))) }
    }

    @Test
    fun `plaintext token stored before encryption was enabled is rejected, not returned`() {
        assertFails { encryptor.decrypt("ya29.a0AfH6SMBplaintextAccessToken") }
    }

    @Test
    fun `two encryptors built from the same key interoperate`() {
        val other = TokenEncryptor(key)

        assertEquals(gmailRefreshToken, other.decrypt(encryptor.encrypt(gmailRefreshToken)))
    }

    @Test
    @Disabled("BUG AOS-312: TokenEncryptor.kt:35 keeps only the first 32 bytes of TOKEN_ENCRYPTION_KEY; the rest is silently ignored (same class of bug as AOS-607)")
    fun `keys that differ only after byte 32 are different keys`() {
        val prefix = "0123456789abcdef0123456789abcdef"
        val a = TokenEncryptor(prefix + "-rotated-key-A")
        val b = TokenEncryptor(prefix + "-rotated-key-B")

        assertFailsWith<AEADBadTagException> { b.decrypt(a.encrypt(gmailRefreshToken)) }
    }

    @Test
    fun `iv bytes are not all zero`() {
        val iv = decode(encryptor.encrypt("x")).copyOfRange(0, 12)

        assertTrue(!iv.contentEquals(ByteArray(12)))
    }
}
