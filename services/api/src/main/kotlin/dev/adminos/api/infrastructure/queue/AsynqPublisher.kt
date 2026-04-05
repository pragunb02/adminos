package dev.adminos.api.infrastructure.queue

import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.future.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * Publishes job payloads to Redis for Go workers to consume.
 *
 * Uses a simple Redis list (`adminos:jobs:pending`) with JSON messages.
 * The Go worker reads from this list and enqueues via Asynq's native client,
 * avoiding fragile coupling to Asynq's internal protobuf wire format.
 */
class AsynqPublisher(private val redisClient: RedisClient) {
    private val connection: StatefulRedisConnection<String, String> = redisClient.connect()
    private val json = Json { ignoreUnknownKeys = true }
    private val logger = LoggerFactory.getLogger(AsynqPublisher::class.java)

    companion object {
        const val JOBS_LIST = "adminos:jobs:pending"
        const val DEFAULT_QUEUE = "default"
        const val DEFAULT_TIMEOUT_SECONDS = 300
        const val DEFAULT_MAX_RETRY = 3
    }

    suspend fun enqueue(
        jobType: String,
        payload: Map<String, Any?>,
        queue: String = DEFAULT_QUEUE
    ): String {
        val taskId = UUID.randomUUID().toString()
        val message = mapOf(
            "id" to taskId,
            "type" to jobType,
            "payload" to json.encodeToString(payload.mapValues { (_, v) -> v?.toString() }),
            "queue" to queue,
            "retry" to DEFAULT_MAX_RETRY.toString(),
            "timeout" to DEFAULT_TIMEOUT_SECONDS.toString(),
            "created_at" to Instant.now().toString()
        )

        val messageJson = json.encodeToString(message)
        connection.async().lpush(JOBS_LIST, messageJson).await()

        logger.info("Enqueued job: type={}, id={}, queue={}", jobType, taskId, queue)
        return taskId
    }

    fun close() {
        connection.close()
        redisClient.shutdown()
    }
}
