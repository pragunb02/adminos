package dev.adminos.api.domain.common.workflow

import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * Routes incoming jobs to the correct handler.
 * Handlers are registered at application startup.
 * Used by: Task 7 (subscription detection cron), Task 9 (agent jobs)
 */
class JobRouter {
    private val logger = LoggerFactory.getLogger(JobRouter::class.java)
    private val handlers = mutableMapOf<JobType, JobHandler<*, *>>()

    fun register(handler: JobHandler<*, *>) {
        handlers[handler.jobType] = handler
        logger.info("Registered handler: {} (trigger={})", handler.jobType.value, handler.triggerType)
    }

    suspend fun dispatch(jobType: JobType, payload: JsonObject) {
        val handler = handlers[jobType]
            ?: throw IllegalArgumentException("No handler registered for job type: ${jobType.value}")

        @Suppress("UNCHECKED_CAST")
        (handler as JobHandler<Any, Any>).run(payload)
    }

    fun registeredTypes(): List<String> = handlers.keys.map { it.value }
}
