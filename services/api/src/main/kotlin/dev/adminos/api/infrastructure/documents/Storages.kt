package dev.adminos.api.infrastructure.documents

import dev.adminos.api.config.DocumentStorageConfig
import dev.adminos.api.domain.documents.DocumentObjectMissingException
import dev.adminos.api.domain.documents.DocumentStorage
import dev.adminos.api.domain.documents.DocumentStorageException
import dev.adminos.api.domain.documents.DocumentStorageNotConfiguredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URI
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** Cloudflare R2 or any S3-compatible store (AOS-507). Holds only ciphertext (see AesGcmDocumentSealer). */
class S3DocumentStorage(private val config: DocumentStorageConfig) : DocumentStorage {
    private val s3: S3Client = S3Client.builder()
        .endpointOverride(URI(config.endpoint))
        .region(Region.of(config.region))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKeyId, config.secretAccessKey)))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .httpClient(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(10)).socketTimeout(Duration.ofSeconds(60)).build())
        .build()

    override suspend fun put(key: String, bytes: ByteArray): Unit = call("store") {
        s3.putObject(PutObjectRequest.builder().bucket(config.bucket).key(key).contentType("application/octet-stream").build(), RequestBody.fromBytes(bytes))
    }

    override suspend fun get(key: String): ByteArray = call("read") {
        try {
            s3.getObjectAsBytes(GetObjectRequest.builder().bucket(config.bucket).key(key).build()).asByteArray()
        } catch (e: NoSuchKeyException) {
            throw DocumentObjectMissingException(key)
        }
    }

    override suspend fun delete(key: String): Unit = call("delete") {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(config.bucket).key(key).build()) // S3 deletes are idempotent
    }

    private suspend fun <T> call(what: String, block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: DocumentStorageException) {
            throw e
        } catch (e: SdkException) {
            throw DocumentStorageException("Couldn't $what the document in storage: ${e::class.simpleName}", e)
        }
    }
}

/** Dev mode without storage settings only: files live in memory and vanish on restart (logged at start-up). */
class InMemoryDocumentStorage : DocumentStorage {
    private val objects = ConcurrentHashMap<String, ByteArray>()
    override suspend fun put(key: String, bytes: ByteArray) { objects[key] = bytes.copyOf() }
    override suspend fun get(key: String) = objects[key]?.copyOf() ?: throw DocumentObjectMissingException(key)
    override suspend fun delete(key: String) { objects.remove(key) }
}

/** With a database but no storage settings, documents are refused clearly rather than kept somewhere unsafe. */
class UnconfiguredDocumentStorage : DocumentStorage {
    override suspend fun put(key: String, bytes: ByteArray) = throw DocumentStorageNotConfiguredException()
    override suspend fun get(key: String) = throw DocumentStorageNotConfiguredException()
    override suspend fun delete(key: String) = throw DocumentStorageNotConfiguredException()
}
