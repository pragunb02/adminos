package dev.adminos.api.infrastructure.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the in-memory rate limiter.
 * Validates Requirement 19.5
 */
class RateLimiterTest {

    @Test
    fun `allows requests within limit`() {
        val limiter = RateLimiter(maxRequests = 3, windowSeconds = 60)

        assertTrue(limiter.isAllowed("ip_1"), "1st request should be allowed")
        assertTrue(limiter.isAllowed("ip_1"), "2nd request should be allowed")
        assertTrue(limiter.isAllowed("ip_1"), "3rd request should be allowed")
    }

    @Test
    fun `blocks requests exceeding limit`() {
        val limiter = RateLimiter(maxRequests = 2, windowSeconds = 60)

        assertTrue(limiter.isAllowed("ip_1"))
        assertTrue(limiter.isAllowed("ip_1"))
        assertFalse(limiter.isAllowed("ip_1"), "3rd request should be blocked")
    }

    @Test
    fun `different keys have independent limits`() {
        val limiter = RateLimiter(maxRequests = 1, windowSeconds = 60)

        assertTrue(limiter.isAllowed("ip_1"), "First IP should be allowed")
        assertFalse(limiter.isAllowed("ip_1"), "First IP should be blocked")
        assertTrue(limiter.isAllowed("ip_2"), "Second IP should be allowed (independent)")
    }

    @Test
    fun `remaining requests decrements correctly`() {
        val limiter = RateLimiter(maxRequests = 5, windowSeconds = 60)

        assertEquals(5, limiter.remainingRequests("ip_1"))
        limiter.isAllowed("ip_1")
        assertEquals(4, limiter.remainingRequests("ip_1"))
        limiter.isAllowed("ip_1")
        assertEquals(3, limiter.remainingRequests("ip_1"))
    }

    @Test
    fun `remaining requests never goes below zero`() {
        val limiter = RateLimiter(maxRequests = 1, windowSeconds = 60)

        limiter.isAllowed("ip_1")
        limiter.isAllowed("ip_1") // over limit
        limiter.isAllowed("ip_1") // way over limit

        assertEquals(0, limiter.remainingRequests("ip_1"))
    }

    @Test
    fun `window resets after expiry`() {
        // Use a 1-second window for fast test
        val limiter = RateLimiter(maxRequests = 1, windowSeconds = 1)

        assertTrue(limiter.isAllowed("ip_1"))
        assertFalse(limiter.isAllowed("ip_1"))

        // Wait for window to expire
        Thread.sleep(1100)

        assertTrue(limiter.isAllowed("ip_1"), "Should be allowed after window reset")
    }
}
