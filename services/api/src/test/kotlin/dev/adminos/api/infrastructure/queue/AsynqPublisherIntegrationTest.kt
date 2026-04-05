package dev.adminos.api.infrastructure.queue

import io.lettuce.core.RedisClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Property 3: Asynq Wire Format Correctness (full integration)
 *
 * Uses Testcontainers Redis to verify that AsynqPublisher.enqueue
 * writes correct JSON to the Redis list with expected fields.
 *
 * **Validates: Requirements 2.3**
 */
@Testcontainers
@Tag("integration")
class AsynqPublisherIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val redis = GenericContainer("redis:7-alpine")
            .withExposedPorts(6379)
    }

    @Test
    fun `enqueue writes correct JSON to Redis list`() = runBlocking {
        val redisUrl = "redis://${redis.host}:${redis.getMappedPort(6379)}"
        val redisClient = RedisClient.create(redisUrl)
        val publisher = AsynqPublisher(redisClient)

        val taskId = publisher.enqueue(
            jobType = "sms_process",
            payload = mapOf("user_id" to "user123", "sync_session_id" to "sess456")
        )

        assertNotNull(taskId)
        assertTrue(taskId.isNotBlank())

        // Read the job from the Redis list
        val verifyClient = RedisClient.create(redisUrl)
        val connection = verifyClient.connect()
        val result = connection.sync().rpop(AsynqPublisher.JOBS_LIST)

        assertNotNull(result, "Expected a job in the Redis list")

        val json = Json.parseToJsonElement(result).jsonObject
        assertEquals("sms_process", json["type"]?.jsonPrimitive?.content)
        assertNotNull(json["id"]?.jsonPrimitive?.content)
        assertEquals("default", json["queue"]?.jsonPrimitive?.content)
        assertNotNull(json["payload"]?.jsonPrimitive?.content)
        assertNotNull(json["created_at"]?.jsonPrimitive?.content)

        // Verify the payload is valid JSON containing our data
        val payloadJson = Json.parseToJsonElement(json["payload"]!!.jsonPrimitive.content).jsonObject
        assertEquals("user123", payloadJson["user_id"]?.jsonPrimitive?.content)
        assertEquals("sess456", payloadJson["sync_session_id"]?.jsonPrimitive?.content)

        connection.close()
        verifyClient.shutdown()
        publisher.close()
    }

    @Test
    fun `enqueue supports all job types`() = runBlocking {
        val redisUrl = "redis://${redis.host}:${redis.getMappedPort(6379)}"
        val redisClient = RedisClient.create(redisUrl)
        val publisher = AsynqPublisher(redisClient)

        val jobTypes = listOf(
            "sms_process", "gmail_ingest", "pdf_parse",
            "agent_briefing", "subscription_detect", "anomaly_check",
            "waste_score", "categorize_fallback", "cancellation_draft"
        )

        for (jobType in jobTypes) {
            val taskId = publisher.enqueue(
                jobType = jobType,
                payload = mapOf("user_id" to "user_${jobType}")
            )
            assertNotNull(taskId, "Task ID should not be null for $jobType")
        }

        // Verify all 9 jobs are in the list
        val verifyClient = RedisClient.create(redisUrl)
        val connection = verifyClient.connect()
        val listLen = connection.sync().llen(AsynqPublisher.JOBS_LIST)
        assertEquals(9L, listLen, "Expected 9 jobs in the Redis list")

        connection.close()
        verifyClient.shutdown()
        publisher.close()
    }

    @Test
    fun `enqueue sets default retry and timeout values`() = runBlocking {
        val redisUrl = "redis://${redis.host}:${redis.getMappedPort(6379)}"
        val redisClient = RedisClient.create(redisUrl)
        val publisher = AsynqPublisher(redisClient)

        publisher.enqueue(
            jobType = "agent_briefing",
            payload = mapOf("user_id" to "user789")
        )

        val verifyClient = RedisClient.create(redisUrl)
        val connection = verifyClient.connect()
        val result = connection.sync().rpop(AsynqPublisher.JOBS_LIST)

        assertNotNull(result)
        val json = Json.parseToJsonElement(result).jsonObject
        assertEquals("3", json["retry"]?.jsonPrimitive?.content)
        assertEquals("300", json["timeout"]?.jsonPrimitive?.content)

        connection.close()
        verifyClient.shutdown()
        publisher.close()
    }
}
