package dev.adminos.api.di

import dev.adminos.api.domain.ingestion.sync.IngestionService
import dev.adminos.api.infrastructure.queue.AsynqPublisher
import io.lettuce.core.RedisClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.dsl.koinApplication
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import dev.adminos.api.domain.ingestion.sync.SyncType

/**
 * With REDIS_URL set, DI wires the queue publisher and Gmail ingests are queued for the Go workers (AOS-312).
 * Redis requires a password, as in compose (AOS-634): REDIS_PASSWORD next to a bare REDIS_URL, or in the URL.
 */
@Testcontainers
@Tag("integration")
class DiRedisWiringIntegrationTest {

    companion object {
        /** Random per run, so no credential sits in the repo. */
        private val password = UUID.randomUUID().toString().replace("-", "")

        @Container
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:8.10.2-alpine")
            .withCommand("redis-server", "--requirepass", password)
            .withExposedPorts(6379)
    }

    private fun url(withPassword: Boolean = true) =
        "redis://" + (if (withPassword) ":$password@" else "") + "${redis.host}:${redis.getMappedPort(6379)}"

    @Test
    fun `Redis refuses a client that has no password`() {
        val client = RedisClient.create(url(withPassword = false))
        try {
            val e = kotlin.test.assertFails { client.connect().use { it.sync().ping() } }
            assertTrue(generateSequence(e) { it.cause }.any { "NOAUTH" in (it.message ?: "") }, e.toString())
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun `a REDIS_URL with the wrong password fails the publisher at start-up`() {
        val wrong = mapOf("REDIS_URL" to url(withPassword = false), "REDIS_PASSWORD" to UUID.randomUUID().toString())
        val koin = koinApplication { modules(buildAppModule(useDatabase = false, env = wrong::get)) }.koin
        kotlin.test.assertFails { koin.get<AsynqPublisher>() }
    }

    @Test
    fun `a Gmail ingest is published to the jobs list when Redis is configured`() = runBlocking<Unit> {
        val url = url()
        // The compose shape: a bare URL and the password beside it.
        val env = mapOf("REDIS_URL" to url.replace(":$password@", ""), "REDIS_PASSWORD" to password)
        val koin = koinApplication { modules(buildAppModule(useDatabase = false, env = env::get)) }.koin
        val publisher = koin.get<AsynqPublisher>()
        try {
            val session = koin.get<IngestionService>().enqueueGmailIngest(UUID.randomUUID(), UUID.randomUUID(), historyId = "4242")
            val client = RedisClient.create(url)
            val job = client.connect().use { it.sync().rpop(AsynqPublisher.JOBS_LIST) }
            client.shutdown()
            val msg = Json.parseToJsonElement(assertNotNull(job)).jsonObject
            assertEquals("gmail_ingest", msg["type"]!!.jsonPrimitive.content)
            val payload = Json.parseToJsonElement(msg["payload"]!!.jsonPrimitive.content).jsonObject
            assertEquals(session.id.toString(), payload["sync_session_id"]!!.jsonPrimitive.content)
            assertEquals("4242", payload["history_id"]!!.jsonPrimitive.content)
        } finally {
            publisher.close()
        }
    }

    @Test
    fun `a fallback Gmail sync carries only string values and no history id key (AOS-401)`() = runBlocking<Unit> {
        val url = url() // a host-run API: the password inside the URL
        val koin = koinApplication { modules(buildAppModule(useDatabase = false, env = mapOf("REDIS_URL" to url)::get)) }.koin
        val publisher = koin.get<AsynqPublisher>()
        try {
            val userId = UUID.randomUUID()
            val connectionId = UUID.randomUUID()
            val session = koin.get<IngestionService>().enqueueGmailIngest(userId, connectionId, syncType = SyncType.CRON_FALLBACK)
            val client = RedisClient.create(url)
            val job = client.connect().use { it.sync().rpop(AsynqPublisher.JOBS_LIST) }
            client.shutdown()
            val msg = Json.parseToJsonElement(assertNotNull(job)).jsonObject
            val payload = Json.parseToJsonElement(msg.getValue("payload").jsonPrimitive.content).jsonObject

            assertEquals(SyncType.CRON_FALLBACK, session.syncType)
            assertEquals(setOf("user_id", "sync_session_id", "connection_id"), payload.keys)
            assertEquals(connectionId.toString(), payload.getValue("connection_id").jsonPrimitive.content)
            assertTrue(payload.values.all { it.jsonPrimitive.isString })
        } finally {
            publisher.close()
        }
    }
}
