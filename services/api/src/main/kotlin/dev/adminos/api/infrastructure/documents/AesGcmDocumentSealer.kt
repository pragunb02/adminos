package dev.adminos.api.infrastructure.documents

import dev.adminos.api.domain.documents.DocumentIntegrityException
import dev.adminos.api.domain.documents.DocumentSealer
import dev.adminos.api.infrastructure.crypto.TokenEncryptor
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Envelope encryption for documents (AOS-507, D-60), JDK crypto only:
 * - each file gets a fresh random 256-bit key; the file is AES-256-GCM encrypted with it, with the
 *   document id as associated data (a file moved under another document's id won't decrypt);
 * - that key is wrapped with the app key ([TokenEncryptor], context `document:<id>`) and stored with the row.
 * Stored object = version byte ‖ 12-byte nonce ‖ ciphertext+tag.
 */
class AesGcmDocumentSealer(private val keys: TokenEncryptor) : DocumentSealer {
    private val random = SecureRandom()

    override fun seal(documentId: UUID, plaintext: ByteArray): DocumentSealer.Sealed {
        val key = ByteArray(32).also(random::nextBytes)
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val cipher = Cipher.getInstance(ALGORITHM).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad(documentId))
        }
        val body = cipher.doFinal(plaintext)
        val stored = ByteBuffer.allocate(1 + NONCE + body.size).put(VERSION).put(nonce).put(body).array()
        val wrapped = keys.encrypt(Base64.getEncoder().encodeToString(key), context(documentId))
        key.fill(0)
        return DocumentSealer.Sealed(stored, wrapped)
    }

    override fun open(documentId: UUID, ciphertext: ByteArray, wrappedKey: String): ByteArray {
        if (ciphertext.size < 1 + NONCE + TAG_BITS / 8 || ciphertext[0] != VERSION) throw DocumentIntegrityException("Unknown or truncated document format")
        val key = try {
            Base64.getDecoder().decode(keys.decrypt(wrappedKey, context(documentId)))
        } catch (e: GeneralSecurityException) {
            throw DocumentIntegrityException("The document key can't be unwrapped", e)
        } catch (e: IllegalArgumentException) {
            throw DocumentIntegrityException("The document key is malformed", e)
        } catch (e: IllegalStateException) {
            throw DocumentIntegrityException("The document key is in an unknown format", e)
        }
        return try {
            Cipher.getInstance(ALGORITHM).run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, ciphertext, 1, NONCE))
                updateAAD(aad(documentId))
                doFinal(ciphertext, 1 + NONCE, ciphertext.size - 1 - NONCE)
            }
        } catch (e: GeneralSecurityException) {
            throw DocumentIntegrityException("The document doesn't decrypt (tampered with, or another document's)", e)
        } finally {
            key.fill(0)
        }
    }

    private fun aad(id: UUID) = "adminos-document:$id".toByteArray()
    private fun context(id: UUID) = "document:$id"

    private companion object {
        const val ALGORITHM = "AES/GCM/NoPadding"
        const val NONCE = 12
        const val TAG_BITS = 128
        const val VERSION: Byte = 1
    }
}
