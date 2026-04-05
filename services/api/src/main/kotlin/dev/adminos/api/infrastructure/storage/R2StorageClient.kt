package dev.adminos.api.infrastructure.storage

import dev.adminos.api.config.R2Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URI

/**
 * Cloudflare R2 storage client using the AWS S3 SDK (S3-compatible).
 */
class R2StorageClient(private val config: R2Config) : StorageClient {

    private val logger = LoggerFactory.getLogger(R2StorageClient::class.java)

    private val s3Client: S3Client = S3Client.builder()
        .endpointOverride(URI.create(config.endpoint))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(config.accessKey, config.secretKey)
            )
        )
        .region(Region.of("auto"))
        .build()

    override suspend fun upload(storageKey: String, content: ByteArray, contentType: String): String = withContext(Dispatchers.IO) {
        logger.info("R2 upload: bucket={}, key={}, size={}", config.bucket, storageKey, content.size)
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(config.bucket)
                .key(storageKey)
                .contentType(contentType)
                .build(),
            RequestBody.fromBytes(content)
        )
        storageKey
    }

    override suspend fun download(storageKey: String): ByteArray = withContext(Dispatchers.IO) {
        logger.info("R2 download: bucket={}, key={}", config.bucket, storageKey)
        val response = s3Client.getObject(
            GetObjectRequest.builder()
                .bucket(config.bucket)
                .key(storageKey)
                .build()
        )
        response.readAllBytes()
    }
}
