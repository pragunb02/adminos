package dev.adminos.api.infrastructure.plugins

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Simple in-memory rate limiter.
 * Will be replaced with Redis-based implementation when Redis is available (task 3.4).
 *
 * Uses a sliding window counter per key (IP address or user ID).
 */
class RateLimiter(
    private val maxRequests: Int,
    private val windowSeconds: Long
) {
    private data class Window(
        val count: AtomicInteger = AtomicInteger(0),
        val windowStart: Long = System.currentTimeMillis()
    )

    private val windows = ConcurrentHashMap<String, Window>()

    fun isAllowed(key: String): Boolean {
        val now = System.currentTimeMillis()

        // Evict expired entries periodically (every 100 calls)
        if (windows.size > 1000) {
            windows.entries.removeIf { now - it.value.windowStart > windowSeconds * 1000 * 2 }
        }

        val window = windows.compute(key) { _, existing ->
            if (existing == null || now - existing.windowStart > windowSeconds * 1000) {
                Window(AtomicInteger(1), now)
            } else {
                existing.count.incrementAndGet()
                existing
            }
        }!!
        return window.count.get() <= maxRequests
    }

    fun remainingRequests(key: String): Int {
        val window = windows[key] ?: return maxRequests
        return (maxRequests - window.count.get()).coerceAtLeast(0)
    }

    fun retryAfterSeconds(key: String): Long {
        val window = windows[key] ?: return 0
        val elapsed = System.currentTimeMillis() - window.windowStart
        return ((windowSeconds * 1000 - elapsed) / 1000).coerceAtLeast(0)
    }
}

/** Rate limiter instances for different endpoint groups */
object RateLimiters {
    val auth = RateLimiter(maxRequests = 10, windowSeconds = 60)        // 10/min per IP
    val userApi = RateLimiter(maxRequests = 60, windowSeconds = 60)     // 60/min per user
    val transactions = RateLimiter(maxRequests = 30, windowSeconds = 60) // 30/min per user
    val manualSync = RateLimiter(maxRequests = 5, windowSeconds = 3600)  // 5/hour per user
    val upload = RateLimiter(maxRequests = 10, windowSeconds = 60)       // 10/min per user
}

/**
 * Ktor plugin that applies rate limiting to auth endpoints by IP address.
 */
val RateLimitPlugin = createRouteScopedPlugin("RateLimit", ::RateLimitConfig) {
    val limiter = pluginConfig.limiter
    val keyExtractor = pluginConfig.keyExtractor

    onCall { call ->
        val key = keyExtractor(call)
        if (!limiter.isAllowed(key)) {
            val retryAfter = limiter.retryAfterSeconds(key)
            val error = ApiError(
                code = "RATE_001",
                message = "Rate limit exceeded. Try again in $retryAfter seconds.",
                details = JsonObject(mapOf(
                    "retryAfterSeconds" to JsonPrimitive(retryAfter)
                ))
            )
            call.respond(
                HttpStatusCode.TooManyRequests,
                ApiResponse.error(error, call.requestId)
            )
        }
    }
}

class RateLimitConfig {
    var limiter: RateLimiter = RateLimiters.auth
    var keyExtractor: (ApplicationCall) -> String = { call ->
        call.request.local.remoteHost
    }
}
