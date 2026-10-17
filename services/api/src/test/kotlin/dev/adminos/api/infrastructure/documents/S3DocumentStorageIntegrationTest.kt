package dev.adminos.api.infrastructure.documents

import dev.adminos.api.config.DocumentStorageConfig
import dev.adminos.api.domain.documents.DocumentObjectMissingException
import dev.adminos.api.domain.documents.DocumentStorageException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import java.net.ServerSocket
import java.net.URI
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * The S3 adapter against a real S3-compatible server, as it will talk to Cloudflare R2 (AOS-507).
 * Adobe S3Mock (Apache-2.0) speaks the S3 API but doesn't check credentials, so auth failures are
 * covered by the R2 manual check instead.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3DocumentStorageIntegrationTest {
    /** Made up per run: never literal credentials. */
    private val user = "u" + UUID.randomUUID().toString().replace("-", "").take(12)
    private val secret = UUID.randomUUID().toString()
    private val s3 = GenericContainer("adobe/s3mock:4.7.0").withExposedPorts(9090).waitingFor(Wait.forHttp("/").forPort(9090))
    private lateinit var storage: S3DocumentStorage
    private lateinit var config: DocumentStorageConfig

    @BeforeAll
    fun start() {
        s3.start()
        config = DocumentStorageConfig(endpoint = "http://127.0.0.1:${s3.getMappedPort(9090)}", region = "auto", bucket = "adminos-docs-test",
            accessKeyId = user, secretAccessKey = secret)
        S3Client.builder().endpointOverride(URI(config.endpoint)).region(Region.of("us-east-1"))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(user, secret)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .httpClient(UrlConnectionHttpClient.create()).build()
            .use { it.createBucket(CreateBucketRequest.builder().bucket(config.bucket).build()) }
        storage = S3DocumentStorage(config)
    }

    @AfterAll
    fun stop() = s3.stop()

    @Test
    fun `objects are stored, read back exactly, and deleted, and deleting twice is fine`() = runBlocking<Unit> {
        val key = "documents/${UUID.randomUUID()}"
        val bytes = ByteArray(300_000) { (it * 31).toByte() }
        storage.put(key, bytes)
        assertContentEquals(bytes, storage.get(key))
        storage.delete(key)
        storage.delete(key)
        assertFailsWith<DocumentObjectMissingException> { storage.get(key) }
    }

    @Test
    fun `an unreachable store or a missing bucket are storage errors, not crashes`() = runBlocking<Unit> {
        assertFailsWith<DocumentStorageException> { S3DocumentStorage(config.copy(bucket = "no-such-bucket")).put("documents/x", byteArrayOf(1)) }
        val closed = ServerSocket(0).use { it.localPort }
        val down = S3DocumentStorage(config.copy(endpoint = "http://127.0.0.1:$closed"))
        assertFailsWith<DocumentStorageException> { down.get("documents/x") }
    }
}
