package dev.adminos.api.domain.documents

import java.util.UUID

/**
 * Where encrypted document bytes live (AOS-507): Cloudflare R2 or any S3-compatible store in
 * production; memory in dev mode only. Implementations never see plaintext.
 */
interface DocumentStorage {
    /** @throws DocumentStorageException */
    suspend fun put(key: String, bytes: ByteArray)
    /** @throws DocumentObjectMissingException if there's no such object; DocumentStorageException otherwise */
    suspend fun get(key: String): ByteArray
    /** Deleting a missing object is fine. @throws DocumentStorageException */
    suspend fun delete(key: String)
}

/** Encrypts a document with its own key and wraps that key with the app's key (envelope encryption). */
interface DocumentSealer {
    fun seal(documentId: UUID, plaintext: ByteArray): Sealed
    /** @throws DocumentIntegrityException if the bytes or key were tampered with or belong to another document */
    fun open(documentId: UUID, ciphertext: ByteArray, wrappedKey: String): ByteArray

    data class Sealed(val ciphertext: ByteArray, val wrappedKey: String)
}

/** The store can't be reached or refused the request; trying later may work. */
open class DocumentStorageException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Object storage isn't configured on this server (no DOCS_S3_* settings). */
class DocumentStorageNotConfiguredException : DocumentStorageException("Documents need object storage: set the DOCS_S3_* settings (Cloudflare R2)")

class DocumentObjectMissingException(key: String) : DocumentStorageException("The stored file is missing ($key)")

/** Decryption or the checksum failed: the stored file isn't what was uploaded. */
class DocumentIntegrityException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
