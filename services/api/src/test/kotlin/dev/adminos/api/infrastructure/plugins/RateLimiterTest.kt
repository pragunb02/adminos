package dev.adminos.api.infrastructure.plugins

import dev.adminos.api.domain.scheduling.TestClock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The in-memory rate limiter on a clock the test moves by hand (AOS-639): no sleeps, no system time. */
class RateLimiterTest {

    private val clock = TestClock(Instant.parse("2026-10-18T06:30:00Z"))
    private fun limiter(maxRequests: Int, windowSeconds: Long = 60, maxKeys: Int = 10_000) =
        RateLimiter(maxRequests = maxRequests, windowSeconds = windowSeconds, maxKeys = maxKeys, clock = clock)

    @Test
    fun `allows requests within limit`() {
        val limiter = limiter(maxRequests = 3)
        repeat(3) { assertTrue(limiter.isAllowed("203.0.113.1"), "request ${it + 1} is within the limit") }
    }

    @Test
    fun `blocks requests exceeding limit`() {
        val limiter = limiter(maxRequests = 2)
        assertTrue(limiter.isAllowed("203.0.113.1"))
        assertTrue(limiter.isAllowed("203.0.113.1"))
        assertFalse(limiter.isAllowed("203.0.113.1"), "3rd request should be blocked")
    }

    @Test
    fun `different keys have independent limits`() {
        val limiter = limiter(maxRequests = 1)
        assertTrue(limiter.isAllowed("203.0.113.1"))
        assertFalse(limiter.isAllowed("203.0.113.1"))
        assertTrue(limiter.isAllowed("203.0.113.2"), "another client has its own allowance")
    }

    @Test
    fun `remaining requests decrements and never goes below zero`() {
        val limiter = limiter(maxRequests = 2)
        assertEquals(2, limiter.remainingRequests("203.0.113.1"))
        limiter.isAllowed("203.0.113.1")
        assertEquals(1, limiter.remainingRequests("203.0.113.1"))
        repeat(3) { limiter.isAllowed("203.0.113.1") }
        assertEquals(0, limiter.remainingRequests("203.0.113.1"))
    }

    @Test
    fun `the window still counts at exactly its length and starts over a millisecond later`() {
        val limiter = limiter(maxRequests = 1)
        assertTrue(limiter.isAllowed("203.0.113.1"))
        clock.advance(Duration.ofSeconds(60))
        assertFalse(limiter.isAllowed("203.0.113.1"), "60 s after the first request is still the same window")
        clock.advance(Duration.ofMillis(1))
        assertTrue(limiter.isAllowed("203.0.113.1"), "a new window")
        assertEquals(0, limiter.remainingRequests("203.0.113.1"))
    }

    @Test
    fun `refused requests do not move the window, so hammering does not reopen it early`() {
        val limiter = limiter(maxRequests = 1)
        limiter.isAllowed("203.0.113.1")
        repeat(10) { clock.advance(Duration.ofSeconds(5)); assertFalse(limiter.isAllowed("203.0.113.1")) }
        clock.advance(Duration.ofSeconds(11))
        assertTrue(limiter.isAllowed("203.0.113.1"), "61 s after the window opened")
    }

    @Test
    fun `retry after counts down with the clock`() {
        val limiter = limiter(maxRequests = 1)
        assertEquals(0, limiter.retryAfterSeconds("203.0.113.1"), "an unknown client waits for nothing")
        limiter.isAllowed("203.0.113.1")
        assertEquals(60, limiter.retryAfterSeconds("203.0.113.1"))
        clock.advance(Duration.ofMillis(30_500))
        assertEquals(29, limiter.retryAfterSeconds("203.0.113.1"))
        clock.advance(Duration.ofMinutes(5))
        assertEquals(0, limiter.retryAfterSeconds("203.0.113.1"), "never negative once the window has passed")
    }

    @Test
    fun `time comes from the injected clock, not the system`() {
        // A clock 25 years in the past: if any reading used the system time, the window would look long expired.
        val limiter = RateLimiter(maxRequests = 1, windowSeconds = 60, clock = Clock.fixed(Instant.parse("2001-01-01T00:00:00Z"), ZoneOffset.UTC))
        assertTrue(limiter.isAllowed("203.0.113.1"))
        assertFalse(limiter.isAllowed("203.0.113.1"))
        assertEquals(60, limiter.retryAfterSeconds("203.0.113.1"))
    }

    @Test
    fun `a limit, window or key bound that is not positive is refused`() {
        assertFailsWith<IllegalArgumentException> { limiter(maxRequests = 0) }
        assertFailsWith<IllegalArgumentException> { limiter(maxRequests = -1) }
        assertFailsWith<IllegalArgumentException> { limiter(maxRequests = 1, windowSeconds = 0) }
        assertFailsWith<IllegalArgumentException> { limiter(maxRequests = 1, windowSeconds = -1) }
        assertFailsWith<IllegalArgumentException> { limiter(maxRequests = 1, windowSeconds = RateLimiter.MAX_WINDOW_SECONDS + 1) }
        assertFailsWith<IllegalArgumentException> { limiter(maxRequests = 1, maxKeys = 0) }
        assertEquals(1, limiter(maxRequests = 1, windowSeconds = RateLimiter.MAX_WINDOW_SECONDS).remainingRequests("203.0.113.1"))
    }

    @Test
    fun `the endpoint limits keep their numbers and take a clock`() {
        val auth = RateLimiters.auth(clock)
        repeat(10) { assertTrue(auth.isAllowed("203.0.113.1")) }
        assertFalse(auth.isAllowed("203.0.113.1"), "10 a minute")
        assertEquals(60, auth.retryAfterSeconds("203.0.113.1"))

        val sync = RateLimiters.manualSync(clock)
        repeat(5) { assertTrue(sync.isAllowed("user-1")) }
        assertFalse(sync.isAllowed("user-1"), "5 an hour")
        assertEquals(3600, sync.retryAfterSeconds("user-1"))

        val retrain = RateLimiters.retrain(clock)
        repeat(6) { assertTrue(retrain.isAllowed("user-1")) }
        assertFalse(retrain.isAllowed("user-1"), "6 an hour")
        clock.advance(Duration.ofSeconds(3601))
        assertTrue(retrain.isAllowed("user-1"))
    }

    @Test
    fun `every factory call is a fresh limiter`() {
        val first = RateLimiters.retrain(clock)
        repeat(6) { first.isAllowed("user-1") }
        assertEquals(6, RateLimiters.retrain(clock).remainingRequests("user-1"), "no counters shared between applications")
    }

    // ── The bounded key map (AOS-618, AUD-16) ──

    private fun RateLimiter.fill(count: Int, prefix: String = "10.0", step: Duration = Duration.ZERO) =
        repeat(count) { isAllowed("$prefix.${it / 256}.${it % 256}"); clock.advance(step) }

    @Test
    fun `the sweep does not run at 1000 keys or fewer`() {
        val limiter = limiter(maxRequests = 1)
        limiter.fill(RateLimiter.EVICT_ABOVE)
        clock.advance(Duration.ofMinutes(10))
        limiter.isAllowed("203.0.113.9")
        assertEquals(RateLimiter.EVICT_ABOVE + 1, limiter.trackedKeys, "expired, but not swept yet")
    }

    @Test
    fun `above 1000 keys, windows more than two lengths old are swept`() {
        val limiter = limiter(maxRequests = 1)
        limiter.fill(RateLimiter.EVICT_ABOVE + 1)
        clock.advance(Duration.ofSeconds(120).plusMillis(1))
        limiter.isAllowed("203.0.113.9")
        assertEquals(1, limiter.trackedKeys, "only the new client is left")
        assertEquals(1, limiter.remainingRequests("10.0.0.0"), "a swept client has a fresh allowance")
    }

    @Test
    fun `the sweep keeps windows less than two lengths old`() {
        val limiter = limiter(maxRequests = 1)
        limiter.fill(RateLimiter.EVICT_ABOVE + 1)
        clock.advance(Duration.ofSeconds(120))
        limiter.isAllowed("203.0.113.9")
        assertEquals(RateLimiter.EVICT_ABOVE + 2, limiter.trackedKeys)
        assertEquals(0, limiter.remainingRequests("10.0.0.0"), "still counted")
    }

    @Test
    fun `under a flood of live keys the oldest windows go first and the bound holds`() {
        val limiter = limiter(maxRequests = 1, maxKeys = 1_500)
        limiter.fill(2_000, step = Duration.ofMillis(1)) // each key one millisecond newer than the last, all live
        assertEquals(1_501, limiter.trackedKeys, "the bound plus the key just added")
        assertEquals(1, limiter.remainingRequests("10.0.0.0"), "the oldest key went first")
        assertEquals(1, limiter.remainingRequests("10.0.1.242"), "key 498 went too")
        assertEquals(0, limiter.remainingRequests("10.0.1.243"), "key 499 is kept")
        assertEquals(0, limiter.remainingRequests("10.0.7.207"), "the newest key is kept")
    }

    @Test
    fun `a flood cannot reset a live client's count unless it is among the oldest`() {
        val limiter = limiter(maxRequests = 1, maxKeys = 1_500)
        limiter.fill(1_000, prefix = "10.1", step = Duration.ofMillis(1))
        clock.advance(Duration.ofSeconds(1))
        limiter.isAllowed("203.0.113.9") // a real client, newer than the first wave
        limiter.fill(600, prefix = "10.2", step = Duration.ofMillis(1))
        assertFalse(limiter.isAllowed("203.0.113.9"), "its window survived the flood")
        assertTrue(limiter.trackedKeys <= 1_501)
    }
}
