package dev.adminos.api.domain.scheduling

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import kotlin.time.toKotlinDuration

/**
 * Runs every registered [ScheduledJob] when its slot comes up (AOS-501, D-50).
 *
 * Each tick, for every job:
 * 1. work out the job's latest slot from its [Schedule];
 * 2. try to [claim][JobRunRepository.claim] it — the claim, not this class, guarantees a slot runs
 *    once across instances and restarts, and decides retries;
 * 3. if claimed, run the job under its timeout and record the outcome.
 *
 * Jobs run one after another within a tick, so one slow job can delay the others by at most its
 * timeout; a failing job never stops the others.
 */
class JobScheduler(
    jobs: List<ScheduledJob>,
    private val runs: JobRunRepository,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = LoggerFactory.getLogger(JobScheduler::class.java)

    val jobs: List<ScheduledJob> = jobs.sortedBy { it.name.value }

    init {
        val duplicates = jobs.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Two scheduled jobs share a name: $duplicates" }
    }

    /** Ticks every [tickEvery] until [scope] ends. Returns the loop's [Job]. */
    fun start(scope: CoroutineScope, tickEvery: Duration): Job {
        require(tickEvery >= Duration.ofSeconds(1)) { "Tick interval must be at least a second, was $tickEvery" }
        logger.info("Scheduler started with {} jobs: {}", jobs.size, jobs.joinToString { "${it.name} (${it.schedule.describe()})" })
        return scope.launch {
            while (isActive) {
                runDue()
                delay(tickEvery.toKotlinDuration())
            }
        }
    }

    /**
     * One tick: runs every job whose current slot can be claimed, and returns what was run.
     * Never throws (except cancellation): a broken job or repository only affects that job.
     */
    suspend fun runDue(): List<JobRun> = jobs.mapNotNull { job ->
        try {
            runIfDue(job)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // e.g. the database is down: skip this job for this tick, try again on the next.
            logger.error("Scheduler could not claim or record job {}", job.name, e)
            null
        }
    }

    private suspend fun runIfDue(job: ScheduledJob): JobRun? {
        val now = clock.instant()
        val claim = SlotClaim(
            job = job.name,
            slot = job.schedule.latestSlot(now),
            now = now,
            // A little longer than the timeout, so a live run never looks abandoned.
            leaseUntil = now.plus(job.timeout).plus(LEASE_MARGIN),
            retry = job.retry
        )
        val run = runs.claim(claim) ?: return null
        logger.info("Job {} started for slot {} (attempt {})", job.name, run.slot, run.attempts)
        val outcome = try {
            execute(job)
        } catch (e: CancellationException) {
            // Shutting down: record a failure so the slot is retried instead of waiting for the lease to expire.
            logger.warn("Job {} was cancelled by shutdown; its slot will be retried", job.name)
            finish(run, RunOutcome.Failed("Cancelled: the app shut down", clock.instant()))
            throw e
        }
        finish(run, outcome)
        return run
    }

    /** Runs [job] under its timeout. Failures become a [RunOutcome.Failed]; only shutdown cancellation escapes. */
    private suspend fun execute(job: ScheduledJob): RunOutcome = try {
        val result = withTimeout(job.timeout.toKotlinDuration()) { job.run() }
        logger.info("Job {} succeeded: {}", job.name, result.counts)
        RunOutcome.Succeeded(result, clock.instant())
    } catch (e: TimeoutCancellationException) {
        logger.warn("Job {} timed out after {}", job.name, job.timeout)
        RunOutcome.Failed("Timed out after ${job.timeout}", clock.instant())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("Job {} failed", job.name, e)
        RunOutcome.Failed(describe(e), clock.instant())
    }

    private suspend fun finish(run: JobRun, outcome: RunOutcome) =
        withContext(NonCancellable) { runs.finish(run.id, outcome) }

    companion object {
        private val LEASE_MARGIN: Duration = Duration.ofMinutes(1)

        /** Exception class and message only (messages from our code carry no personal data), capped. */
        internal fun describe(e: Throwable) = "${e::class.simpleName}: ${e.message.orEmpty()}".take(500)
    }
}
