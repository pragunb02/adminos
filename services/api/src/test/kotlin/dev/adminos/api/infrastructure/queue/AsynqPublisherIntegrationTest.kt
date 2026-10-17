package dev.adminos.api.infrastructure.queue

import io.lettuce.core.RedisClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        val redis = GenericContainer("redis:8.10.2-alpine")
            .withExposedPorts(6379)
    }

    /** Tests share one Redis container; start each from an empty database so order doesn't matter. */
    @BeforeEach
    fun flushRedis() {
        val client = RedisClient.create("redis://${redis.host}:${redis.getMappedPort(6379)}")
        client.connect().use { it.sync().flushall() }
        client.shutdown()
    }

    @Test
    fun `enqueue writes correct JSON to Redis list`() = runBlocking<Unit> {
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
    fun `payload values keep their JSON types, so Go can read numbers, lists and objects (AOS-506)`() = runBlocking<Unit> {
        val redisUrl = "redis://${redis.host}:${redis.getMappedPort(6379)}"
        val publisher = AsynqPublisher(RedisClient.create(redisUrl))
        publisher.enqueue("waste_score", mapOf(
            "user_id" to "u-1", "amount" to java.math.BigDecimal("649.50"), "count" to 3, "flag" to true,
            "ids" to listOf("a", "b"), "nested" to mapOf("k" to 1), "none" to null
        ))
        val verify = RedisClient.create(redisUrl)
        val conn = verify.connect()
        val job = Json.parseToJsonElement(conn.sync().rpop(AsynqPublisher.JOBS_LIST)).jsonObject
        val payload = Json.parseToJsonElement(job["payload"]!!.jsonPrimitive.content).jsonObject
        assertEquals("649.50", payload["amount"]!!.jsonPrimitive.content)
        assertTrue(!payload["amount"]!!.jsonPrimitive.isString, "a number, not a string")
        assertEquals(3, payload["count"]!!.jsonPrimitive.int)
        assertEquals(true, payload["flag"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("a", "b"), payload["ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, payload["nested"]!!.jsonObject["k"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, payload["none"])
        conn.close(); verify.shutdown(); publisher.close()
    }

    @Test
    fun `enqueue supports all job types`() = runBlocking<Unit> {
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
    fun `enqueue sets default retry and timeout values`() = runBlocking<Unit> {
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
