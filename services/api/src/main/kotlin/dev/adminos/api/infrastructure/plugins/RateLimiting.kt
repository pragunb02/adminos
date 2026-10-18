package dev.adminos.api.infrastructure.plugins

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory fixed-window rate limiter, one counter per key (client IP or user ID).
 *
 * Bounded (AOS-618, AUD-16): at most [maxKeys] keys are tracked. Expired windows are dropped once more than
 * 1000 keys exist; if the map is still over [maxKeys] (a flood of distinct keys), the oldest windows go first,
 * so memory can't grow without limit. Each application builds its own limiters (no global state).
 *
 * Time comes only from [clock] (AOS-639), so tests move it by hand instead of sleeping.
 */
class RateLimiter(
    private val maxRequests: Int,
    private val windowSeconds: Long,
    private val maxKeys: Int = 10_000,
    private val clock: Clock = Clock.systemUTC()
) {
    init {
        require(maxRequests > 0) { "maxRequests must be positive" }
        require(windowSeconds in 1..MAX_WINDOW_SECONDS) { "windowSeconds must be 1..$MAX_WINDOW_SECONDS" }
        require(maxKeys > 0) { "maxKeys must be positive" }
    }

    private class Window(val start: Long) {
        val count = AtomicInteger(0)
    }

    private val windows = ConcurrentHashMap<String, Window>()
    private val windowMillis = windowSeconds * 1000

    fun isAllowed(key: String): Boolean {
        val now = clock.millis()
        if (windows.size > EVICT_ABOVE) evict(now)
        // The remapping never returns null, so neither does compute; if it ever did, fail closed (an error, not a free pass).
        val window = checkNotNull(windows.compute(key) { _, existing ->
            if (existing == null || now - existing.start > windowMillis) Window(now) else existing
        }) { "No rate-limit window was stored" }
        return window.count.incrementAndGet() <= maxRequests
    }

    private fun evict(now: Long) {
        windows.entries.removeIf { now - it.value.start > windowMillis * 2 }
        val excess = windows.size - maxKeys
        if (excess > 0) {
            windows.entries.sortedBy { it.value.start }.take(excess).forEach { windows.remove(it.key, it.value) }
        }
    }

    /** Keys currently tracked (for tests and monitoring). */
    val trackedKeys: Int get() = windows.size

    fun remainingRequests(key: String): Int {
        val window = windows[key] ?: return maxRequests
        return (maxRequests - window.count.get()).coerceAtLeast(0)
    }

    fun retryAfterSeconds(key: String): Long {
        val window = windows[key] ?: return 0
        val elapsed = clock.millis() - window.start
        return ((windowMillis - elapsed) / 1000).coerceAtLeast(0)
    }

    companion object {
        /** Expired windows are swept only once more keys than this are tracked. */
        const val EVICT_ABOVE = 1000
        /** A day: longer windows aren't a rate limit, and this keeps the millisecond maths far from overflow. */
        const val MAX_WINDOW_SECONDS = 86_400L
    }
}

/** The limits per endpoint group. Factories: every application (and every test app) gets fresh counters. */
object RateLimiters {
    fun auth(clock: Clock = Clock.systemUTC()) = RateLimiter(maxRequests = 10, windowSeconds = 60, clock = clock)          // 10/min per client
    fun manualSync(clock: Clock = Clock.systemUTC()) = RateLimiter(maxRequests = 5, windowSeconds = 3600, clock = clock)   // 5/hour per user
    /** Retraining is CPU work: a few per hour per user is plenty for a "retrain now" button (AOS-707). */
    fun retrain(clock: Clock = Clock.systemUTC()) = RateLimiter(maxRequests = 6, windowSeconds = 3600, clock = clock)      // 6/hour per user
    fun forecast(clock: Clock = Clock.systemUTC()) = RateLimiter(maxRequests = 30, windowSeconds = 60, clock = clock)      // 30/min per user: month-end forecast (AOS-714)
    fun cashParse(clock: Clock = Clock.systemUTC()) = RateLimiter(maxRequests = 30, windowSeconds = 60, clock = clock)     // 30/min per user: cash text previews (AOS-718)
    fun documentRead(clock: Clock = Clock.systemUTC()) = RateLimiter(maxRequests = 10, windowSeconds = 60, clock = clock)  // 10/min per user: document OCR is heavy (AOS-717)
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
            call.response.header(HttpHeaders.RetryAfter, retryAfter.toString())
            call.respond(
                HttpStatusCode.TooManyRequests,
                ApiResponse.error(error, call.requestId)
            )
        }
    }
}

class RateLimitConfig {
    var limiter: RateLimiter = RateLimiters.auth()
    var keyExtractor: (ApplicationCall) -> String = { call ->
        call.clientIp
    }
}
