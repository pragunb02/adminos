package dev.adminos.api.infrastructure.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption for secrets at rest, e.g. OAuth tokens (OWASP Cryptographic Storage).
 *
 * - **Key:** `TOKEN_ENCRYPTION_KEY` is base64 of exactly 32 random bytes (`openssl rand -base64 32`).
 *   The whole key is used (AOS-608; it used to be the first 32 *characters* of the text).
 * - **Format:** `v2:` + base64(iv[12] ‖ ciphertext ‖ tag[16]).
 * - **Context:** every value is bound to where it lives (e.g. `user_connections:<id>:access_token`)
 *   as GCM associated data, so a ciphertext copied to another row or field fails to decrypt.
 * - **Legacy:** values written before AOS-608 (no `v2:` prefix) still decrypt with the old key
 *   derivation so they can be re-encrypted ([needsReEncryption]); nothing new is written that way.
 */
class TokenEncryptor(encryptionKey: String) {

    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH_BITS = 128
        private const val KEY_LENGTH = 32 // 256 bits
        private const val PREFIX = "v2:"
        const val KEY_HELP = "TOKEN_ENCRYPTION_KEY must be base64 of 32 random bytes. Generate one with: openssl rand -base64 32"
    }

    private val secretKey: SecretKeySpec
    /** The pre-AOS-608 key (first 32 UTF-8 bytes of the text), only for reading old values. */
    private val legacyKey: SecretKeySpec?
    private val random = SecureRandom()

    init {
        val decoded = runCatching { Base64.getDecoder().decode(encryptionKey.trim()) }.getOrNull()
        require(decoded != null && decoded.size == KEY_LENGTH) { KEY_HELP }
        secretKey = SecretKeySpec(decoded, "AES")
        legacyKey = encryptionKey.toByteArray(Charsets.UTF_8).takeIf { it.size >= KEY_LENGTH }
            ?.let { SecretKeySpec(it.copyOf(KEY_LENGTH), "AES") }
    }

    /** Encrypts [plaintext] bound to [context] (where the value is stored). */
    fun encrypt(plaintext: String, context: String): String {
        val iv = ByteArray(IV_LENGTH).also(random::nextBytes)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    /**
     * Decrypts a value written by [encrypt] with the same [context]. Throws if the key, the context
     * or the bytes don't match (GCM authentication), so tampering is never silently accepted.
     */
    fun decrypt(encrypted: String, context: String): String {
        if (!encrypted.startsWith(PREFIX)) return decryptLegacy(encrypted)
        val combined = Base64.getDecoder().decode(encrypted.removePrefix(PREFIX))
        require(combined.size > IV_LENGTH) { "encrypted value is too short" }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BITS, combined.copyOfRange(0, IV_LENGTH)))
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(combined.copyOfRange(IV_LENGTH, combined.size)), Charsets.UTF_8)
    }

    /** True for values written before AOS-608: decrypt them, then store [encrypt]'s result instead. */
    fun needsReEncryption(encrypted: String): Boolean = !encrypted.startsWith(PREFIX)

    private fun decryptLegacy(encrypted: String): String {
        val key = legacyKey ?: throw IllegalStateException("legacy value but no legacy key can be derived from TOKEN_ENCRYPTION_KEY")
        val combined = Base64.getDecoder().decode(encrypted)
        require(combined.size > IV_LENGTH) { "encrypted value is too short" }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, combined.copyOfRange(0, IV_LENGTH)))
        return String(cipher.doFinal(combined.copyOfRange(IV_LENGTH, combined.size)), Charsets.UTF_8)
    }
}
