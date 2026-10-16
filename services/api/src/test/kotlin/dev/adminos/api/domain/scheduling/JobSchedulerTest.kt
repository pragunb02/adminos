package dev.adminos.api.domain.scheduling

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JobSchedulerTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    /** 10:00 IST on 16 Oct: the 09:00 slot has started. */
    private val clock = TestClock(Instant.parse("2026-10-16T04:30:00Z"))
    private val runs = InMemoryJobRunRepository()

    /** A job whose behaviour each test sets; counts its runs. */
    private class FakeJob(
        name: String,
        override val timeout: Duration = Duration.ofMinutes(10),
        override val retry: RetryPolicy = RetryPolicy(3, Duration.ofMinutes(15)),
        var body: suspend () -> JobResult = { JobResult.of("done" to 1) }
    ) : ScheduledJob {
        override val name = JobName(name)
        override val schedule: Schedule = Schedule.Daily(LocalTime.of(9, 0), ZoneId.of("Asia/Kolkata"))
        var calls = 0
        override suspend fun run(): JobResult { calls++; return body() }
    }

    private fun scheduler(vararg jobs: ScheduledJob, repo: JobRunRepository = runs) = JobScheduler(jobs.toList(), repo, clock)

    @Test
    fun `a due job runs once per slot`() = runBlocking<Unit> {
        val job = FakeJob("bill-reminders")
        val s = scheduler(job)
        assertEquals(1, s.runDue().size)
        clock.advance(Duration.ofMinutes(1))
        assertEquals(0, s.runDue().size)
        assertEquals(1, job.calls)
        val run = runs.latest(job.name)!!
        assertEquals(JobRunStatus.SUCCEEDED, run.status)
        assertEquals(mapOf("done" to 1), run.result?.counts)
        assertEquals(Instant.parse("2026-10-16T03:30:00Z"), run.slot)
    }

    @Test
    fun `the next day's slot runs again`() = runBlocking<Unit> {
        val job = FakeJob("bill-reminders")
        val s = scheduler(job)
        s.runDue()
        clock.advance(Duration.ofDays(1))
        s.runDue()
        assertEquals(2, job.calls)
    }

    @Test
    fun `after days of downtime a job catches up once, not once per missed day`() = runBlocking<Unit> {
        val job = FakeJob("bill-reminders")
        val s = scheduler(job)
        s.runDue()
        clock.advance(Duration.ofDays(3))
        s.runDue(); s.runDue()
        assertEquals(2, job.calls)
    }

    @Test
    fun `a failing job is recorded, retried after the delay, and doesn't stop other jobs`() = runBlocking<Unit> {
        val broken = FakeJob("aaa-broken", body = { throw IOException("database went away") })
        val fine = FakeJob("zzz-fine")
        val s = scheduler(broken, fine)
        s.runDue()
        assertEquals(1, fine.calls)
        val failed = runs.latest(broken.name)!!
        assertEquals(JobRunStatus.FAILED, failed.status)
        assertEquals("IOException: database went away", failed.error)

        clock.advance(Duration.ofMinutes(10))
        s.runDue()
        assertEquals(1, broken.calls, "not before the retry delay")

        broken.body = { JobResult.of("done" to 1) }
        clock.advance(Duration.ofMinutes(6))
        s.runDue()
        assertEquals(2, broken.calls)
        assertEquals(JobRunStatus.SUCCEEDED, runs.latest(broken.name)!!.status)
        assertEquals(2, runs.latest(broken.name)!!.attempts)
    }

    @Test
    fun `a job that runs past its timeout is cancelled and recorded as failed`() = runBlocking<Unit> {
        val slow = FakeJob("slow-job", timeout = Duration.ofMillis(50), body = { delay(5_000); JobResult() })
        scheduler(slow).runDue()
        val run = runs.latest(slow.name)!!
        assertEquals(JobRunStatus.FAILED, run.status)
        assertEquals("Timed out after PT0.05S", run.error)
    }

    @Test
    fun `when claiming fails (database down) the tick goes on with the other jobs`() = runBlocking<Unit> {
        val fine = FakeJob("zzz-fine")
        val flaky = object : JobRunRepository by runs {
            override suspend fun claim(claim: SlotClaim): JobRun? =
                if (claim.job.value == "aaa-first") throw IOException("connection refused") else runs.claim(claim)
        }
        val ran = scheduler(FakeJob("aaa-first"), fine, repo = flaky).runDue()
        assertEquals(listOf(fine.name), ran.map { it.job })
    }

    @Test
    fun `shutdown during a run records a failure so the slot is retried, then stops`() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val job = FakeJob("long-job", body = { started.complete(Unit); awaitCancellation() })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val loop = scheduler(job).start(scope, Duration.ofSeconds(1))
        withTimeout(5_000) { started.await() }
        loop.cancelAndJoin()
        val run = runs.latest(job.name)!!
        assertEquals(JobRunStatus.FAILED, run.status)
        assertEquals("Cancelled: the app shut down", run.error)
    }

    @Test
    fun `the loop runs due jobs right away and keeps ticking`() = runBlocking<Unit> {
        val ran = CompletableDeferred<Unit>()
        val job = FakeJob("quick-job", body = { ran.complete(Unit); JobResult() })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val loop = scheduler(job).start(scope, Duration.ofSeconds(1))
        withTimeout(5_000) { ran.await() }
        assertTrue(loop.isActive)
        loop.cancelAndJoin()
    }

    @Test
    fun `two jobs can't share a name, and the tick must be at least a second`() {
        assertFailsWith<IllegalArgumentException> { scheduler(FakeJob("same-name"), FakeJob("same-name")) }
        assertFailsWith<IllegalArgumentException> {
            scheduler(FakeJob("a-job")).start(CoroutineScope(Dispatchers.Default), Duration.ofMillis(500))
        }
    }

    @Test
    fun `jobs are listed in name order`() {
        assertEquals(listOf("a-job", "b-job"), scheduler(FakeJob("b-job"), FakeJob("a-job")).jobs.map { it.name.value })
    }

    @Test
    fun `error text is the exception class and message, capped`() {
        assertEquals("IllegalStateException: ", JobScheduler.describe(IllegalStateException()))
        assertEquals(500, JobScheduler.describe(RuntimeException("x".repeat(900))).length)
    }
}
