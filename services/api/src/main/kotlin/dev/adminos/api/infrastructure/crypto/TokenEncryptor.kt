package dev.adminos.api.infrastructure.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption for sensitive tokens (OAuth access/refresh tokens).
 * OWASP Cryptographic Storage Cheat Sheet: encrypt sensitive data at rest.
 *
 * Format: base64(iv + ciphertext + tag)
 * IV: 12 bytes (GCM recommended)
 * Tag: 128 bits (GCM default)
 */
class TokenEncryptor(encryptionKey: String) {

    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH_BITS = 128
        private const val KEY_LENGTH = 32 // 256 bits
    }

    private val secretKey: SecretKeySpec
    private val random = SecureRandom()

    init {
        val keyBytes = encryptionKey.toByteArray(Charsets.UTF_8)
        require(keyBytes.size >= KEY_LENGTH) {
            "TOKEN_ENCRYPTION_KEY must be at least 32 bytes. Current: ${keyBytes.size} bytes. " +
            "Generate one with: openssl rand -base64 32"
        }
        secretKey = SecretKeySpec(keyBytes.copyOf(KEY_LENGTH), "AES")
    }

    /**
     * Encrypt a plaintext string. Returns base64-encoded ciphertext.
     */
    fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_LENGTH)
        random.nextBytes(iv)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))

        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Concatenate IV + ciphertext (GCM tag is appended by the cipher)
        val combined = iv + ciphertext
        return Base64.getEncoder().encodeToString(combined)
    }

    /**
     * Decrypt a base64-encoded ciphertext. Returns the original plaintext.
     */
    fun decrypt(encrypted: String): String {
        val combined = Base64.getDecoder().decode(encrypted)

        val iv = combined.copyOfRange(0, IV_LENGTH)
        val ciphertext = combined.copyOfRange(IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))

        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }
}
