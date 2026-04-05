package dev.adminos.api.infrastructure.storage

import org.slf4j.LoggerFactory

/**
 * No-op storage client for local dev without R2 credentials.
 * Logs operations and returns the key / empty bytes.
 */
class StubStorageClient : StorageClient {

    private val logger = LoggerFactory.getLogger(StubStorageClient::class.java)

    override suspend fun upload(storageKey: String, content: ByteArray, contentType: String): String {
        logger.info("Stub upload: {} ({} bytes, type={})", storageKey, content.size, contentType)
        return storageKey
    }

    override suspend fun download(storageKey: String): ByteArray {
        logger.info("Stub download: {}", storageKey)
        return ByteArray(0)
    }
}
