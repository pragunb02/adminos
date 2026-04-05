package dev.adminos.api.infrastructure.storage

/**
 * Abstraction for object storage (R2, S3, or stub for dev mode).
 */
interface StorageClient {
    suspend fun upload(storageKey: String, content: ByteArray, contentType: String = "application/pdf"): String
    suspend fun download(storageKey: String): ByteArray
}
