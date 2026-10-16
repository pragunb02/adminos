package dev.adminos.api.domain.scheduling

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

/** In-memory [JobRunRepository] (dev mode and unit tests); same rules as the Postgres one, guarded by a mutex. */
class InMemoryJobRunRepository : JobRunRepository {
    private val mutex = Mutex()
    private val runs = mutableMapOf<Pair<JobName, Instant>, JobRun>()

    override suspend fun claim(claim: SlotClaim): JobRun? = mutex.withLock {
        val key = claim.job to claim.slot
        val existing = runs[key]
        val started = when {
            existing == null -> newRun(claim)
            ClaimRules.canRetry(existing, claim) -> existing.copy(
                status = JobRunStatus.RUNNING, attempts = existing.attempts + 1, startedAt = claim.now,
                leaseUntil = claim.leaseUntil, finishedAt = null, result = null, error = null
            )
            else -> return@withLock null
        }
        runs[key] = started
        started
    }

    override suspend fun finish(id: UUID, outcome: RunOutcome): Unit = mutex.withLock {
        val (key, run) = runs.entries.firstOrNull { it.value.id == id }?.toPair() ?: return@withLock
        if (run.status != JobRunStatus.RUNNING) return@withLock
        runs[key] = when (outcome) {
            is RunOutcome.Succeeded -> run.copy(status = JobRunStatus.SUCCEEDED, finishedAt = outcome.finishedAt, result = outcome.result)
            is RunOutcome.Failed -> run.copy(status = JobRunStatus.FAILED, finishedAt = outcome.finishedAt, error = outcome.error)
        }
    }

    override suspend fun latest(job: JobName): JobRun? = mutex.withLock {
        runs.values.filter { it.job == job }.maxByOrNull { it.slot }
    }

    private fun newRun(claim: SlotClaim) = JobRun(
        id = UUID.randomUUID(), job = claim.job, slot = claim.slot, status = JobRunStatus.RUNNING,
        attempts = 1, startedAt = claim.now, leaseUntil = claim.leaseUntil
    )
}

/** The retry rule from [JobRunRepository.claim], shared so both repositories decide the same way. */
internal object ClaimRules {
    fun canRetry(run: JobRun, claim: SlotClaim): Boolean {
        if (run.attempts >= claim.retry.maxAttempts) return false
        return when (run.status) {
            JobRunStatus.SUCCEEDED -> false
            JobRunStatus.RUNNING -> run.leaseUntil.isBefore(claim.now)
            JobRunStatus.FAILED -> !(run.finishedAt ?: run.startedAt).plus(claim.retry.delay).isAfter(claim.now)
        }
    }
}
