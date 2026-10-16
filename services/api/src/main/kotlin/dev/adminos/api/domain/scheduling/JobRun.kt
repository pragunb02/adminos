package dev.adminos.api.domain.scheduling

import java.time.Instant
import java.util.UUID

enum class JobRunStatus { RUNNING, SUCCEEDED, FAILED }

/** One attempt history for one slot of one job (row in `job_runs`). */
data class JobRun(
    val id: UUID,
    val job: JobName,
    val slot: Instant,
    val status: JobRunStatus,
    /** How many times this slot has been started (1 on the first run). */
    val attempts: Int,
    val startedAt: Instant,
    /** While RUNNING, a run whose lease has passed is assumed abandoned (crashed instance) and may be retried. */
    val leaseUntil: Instant,
    val finishedAt: Instant? = null,
    val result: JobResult? = null,
    /** Exception class and message, no personal data (see [ScheduledJob]). */
    val error: String? = null
) {
    init {
        require(attempts >= 1) { "attempts starts at 1, was $attempts" }
        require(!leaseUntil.isBefore(startedAt)) { "A lease can't end before the run started" }
    }
}

/** Everything needed to claim a slot; see [JobRunRepository.claim]. */
data class SlotClaim(
    val job: JobName,
    val slot: Instant,
    val now: Instant,
    val leaseUntil: Instant,
    val retry: RetryPolicy
) {
    init {
        require(leaseUntil.isAfter(now)) { "The lease must end after now" }
    }
}

/** What finished a run: success with counts, or failure with a reason. */
sealed interface RunOutcome {
    val finishedAt: Instant

    data class Succeeded(val result: JobResult, override val finishedAt: Instant) : RunOutcome
    data class Failed(val error: String, override val finishedAt: Instant) : RunOutcome
}

/**
 * Run bookkeeping. [claim] is the **only** gate to running a slot, and it is atomic across API
 * instances (Postgres row lock + unique (job, slot)), so a slot never runs twice at the same time.
 */
interface JobRunRepository {
    /**
     * Starts a run of `claim.job` for `claim.slot` and returns it, or returns null when the slot
     * must not run now. A slot can be claimed when:
     * - it has never run;
     * - its last attempt FAILED, fewer than `retry.maxAttempts` attempts were made, and `retry.delay` has passed;
     * - it is RUNNING but its lease expired (the instance running it died), with attempts left.
     * A SUCCEEDED slot is never claimed again.
     */
    suspend fun claim(claim: SlotClaim): JobRun?

    /** Records the end of run [id]. A run that is no longer RUNNING is left untouched. */
    suspend fun finish(id: UUID, outcome: RunOutcome)

    /** The run of [job] with the latest slot, if any (for status and tests). */
    suspend fun latest(job: JobName): JobRun?
}
