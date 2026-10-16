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

/** With REDIS_URL set, DI wires the queue publisher and Gmail ingests are queued for the Go workers (AOS-312). */
@Testcontainers
@Tag("integration")
class DiRedisWiringIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:8.10.2-alpine").withExposedPorts(6379)
    }

    @Test
    fun `a Gmail ingest is published to the jobs list when Redis is configured`() = runBlocking<Unit> {
        val url = "redis://${redis.host}:${redis.getMappedPort(6379)}"
        val koin = koinApplication { modules(buildAppModule(useDatabase = false, env = mapOf("REDIS_URL" to url)::get)) }.koin
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
}
