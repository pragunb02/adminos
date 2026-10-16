package dev.adminos.api.domain.scheduling

import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SchedulingModelTest {
    @Test
    fun `job names are lowercase kebab-case`() {
        assertEquals("bill-reminders", JobName("bill-reminders").toString())
        listOf("", "a", "Bill", "bill_reminders", "9am", "-x", "x".repeat(64)).forEach { bad ->
            assertFailsWith<IllegalArgumentException>(bad) { JobName(bad) }
        }
    }

    @Test
    fun `retry policies need at least one attempt and a non-negative delay`() {
        assertFailsWith<IllegalArgumentException> { RetryPolicy(0, Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(1, Duration.ofSeconds(-1)) }
        assertEquals(3, RetryPolicy.DEFAULT.maxAttempts)
    }

    @Test
    fun `results hold counts, never negative`() {
        assertEquals(mapOf("sent" to 2), JobResult.of("sent" to 2).counts)
        assertFailsWith<IllegalArgumentException> { JobResult.of("sent" to -1) }
    }

    @Test
    fun `a run starts at attempt one and its lease can't end before it started`() {
        val t = Instant.parse("2026-10-16T00:00:00Z")
        fun run(attempts: Int, lease: Instant) = JobRun(UUID.randomUUID(), JobName("x-job"), t, JobRunStatus.RUNNING, attempts, t, lease)
        assertFailsWith<IllegalArgumentException> { run(0, t) }
        assertFailsWith<IllegalArgumentException> { run(1, t.minusSeconds(1)) }
    }

    @Test
    fun `a claim's lease must end after now`() {
        val t = Instant.parse("2026-10-16T00:00:00Z")
        assertFailsWith<IllegalArgumentException> { SlotClaim(JobName("x-job"), t, t, t, RetryPolicy.DEFAULT) }
    }
}
