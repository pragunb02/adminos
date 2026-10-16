package dev.adminos.api.domain.scheduling

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The claim rules every [JobRunRepository] must follow (AOS-501). Run against the in-memory
 * repository (unit) and Postgres (integration), so both behave the same (Liskov, ENGINEERING §5).
 */
abstract class JobRunRepositoryContract {
    abstract fun repository(): JobRunRepository

    private val repo by lazy { repository() }
    /** A fresh job per test, so tests sharing one database never meet. */
    private val job = JobName("t-" + UUID.randomUUID().toString().take(8))
    private val slot = Instant.parse("2026-10-16T03:30:00Z")
    private val t0 = Instant.parse("2026-10-16T03:30:05Z")
    private val retry = RetryPolicy(maxAttempts = 3, delay = Duration.ofMinutes(15))

    private fun claimAt(now: Instant, lease: Duration = Duration.ofMinutes(11), at: Instant = slot) =
        SlotClaim(job, at, now, now.plus(lease), retry)

    private suspend fun fail(run: JobRun, at: Instant) = repo.finish(run.id, RunOutcome.Failed("IOException: boom", at))

    @Test
    fun `the first claim of a slot starts attempt one`() = runBlocking<Unit> {
        val run = assertNotNull(repo.claim(claimAt(t0)))
        assertEquals(JobRunStatus.RUNNING, run.status)
        assertEquals(1, run.attempts)
        assertEquals(slot, run.slot)
        assertEquals(job, run.job)
    }

    @Test
    fun `a running slot with a live lease can't be claimed again`() = runBlocking<Unit> {
        repo.claim(claimAt(t0))
        assertNull(repo.claim(claimAt(t0.plusSeconds(30))))
    }

    @Test
    fun `a succeeded slot is never claimed again`() = runBlocking<Unit> {
        val run = assertNotNull(repo.claim(claimAt(t0)))
        repo.finish(run.id, RunOutcome.Succeeded(JobResult.of("sent" to 2), t0.plusSeconds(1)))
        assertNull(repo.claim(claimAt(t0.plus(Duration.ofDays(1)))))
        val stored = assertNotNull(repo.latest(job))
        assertEquals(JobRunStatus.SUCCEEDED, stored.status)
        assertEquals(mapOf("sent" to 2), stored.result?.counts)
    }

    @Test
    fun `a failed slot is retried only after the delay`() = runBlocking<Unit> {
        fail(assertNotNull(repo.claim(claimAt(t0))), t0.plusSeconds(1))
        assertNull(repo.claim(claimAt(t0.plus(Duration.ofMinutes(14)))))
        val retried = assertNotNull(repo.claim(claimAt(t0.plusSeconds(1).plus(Duration.ofMinutes(15)))))
        assertEquals(2, retried.attempts)
        assertNull(retried.error, "a retry starts clean")
    }

    @Test
    fun `a slot that failed every allowed attempt waits for the next slot`() = runBlocking<Unit> {
        var now = t0
        repeat(3) {
            fail(assertNotNull(repo.claim(claimAt(now)), "attempt ${it + 1}"), now)
            now = now.plus(Duration.ofMinutes(16))
        }
        assertNull(repo.claim(claimAt(now)))
        assertEquals("IOException: boom", repo.latest(job)?.error)
        assertNotNull(repo.claim(claimAt(now, at = slot.plus(Duration.ofDays(1)))), "the next slot is independent")
    }

    @Test
    fun `a running slot whose lease expired (crashed instance) is taken over`() = runBlocking<Unit> {
        repo.claim(claimAt(t0, lease = Duration.ofMinutes(1)))
        val takeover = assertNotNull(repo.claim(claimAt(t0.plus(Duration.ofMinutes(2)))))
        assertEquals(2, takeover.attempts)
    }

    @Test
    fun `a late finish from an instance that lost its lease is ignored`() = runBlocking<Unit> {
        val first = assertNotNull(repo.claim(claimAt(t0, lease = Duration.ofMinutes(1))))
        val second = assertNotNull(repo.claim(claimAt(t0.plus(Duration.ofMinutes(2)))))
        repo.finish(second.id, RunOutcome.Succeeded(JobResult.of("sent" to 1), t0.plus(Duration.ofMinutes(3))))
        fail(first, t0.plus(Duration.ofMinutes(4)))
        assertEquals(JobRunStatus.SUCCEEDED, repo.latest(job)?.status)
    }

    @Test
    fun `finishing an unknown run changes nothing`() = runBlocking<Unit> {
        repo.finish(UUID.randomUUID(), RunOutcome.Failed("x", t0))
        assertNull(repo.latest(job))
    }

    @Test
    fun `latest is the run with the newest slot`() = runBlocking<Unit> {
        repo.claim(claimAt(t0))
        repo.claim(claimAt(t0, at = slot.minus(Duration.ofDays(1))))
        assertEquals(slot, repo.latest(job)?.slot)
    }

    @Test
    fun `of many simultaneous claims exactly one wins`() = runBlocking<Unit> {
        val winners = (1..8).map { async(Dispatchers.IO) { repo.claim(claimAt(t0)) } }.awaitAll().filterNotNull()
        assertEquals(1, winners.size)
    }
}

class InMemoryJobRunRepositoryTest : JobRunRepositoryContract() {
    override fun repository(): JobRunRepository = InMemoryJobRunRepository()
}
