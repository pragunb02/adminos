package dev.adminos.api.domain.scheduling

import java.time.Duration

/** A job's stable identity: lowercase kebab-case, used as the key in `job_runs` and in logs. */
@JvmInline
value class JobName(val value: String) {
    init {
        require(value.matches(Regex("[a-z][a-z0-9-]{1,62}"))) { "Job names are lowercase kebab-case (2–63 chars), was '$value'" }
    }

    override fun toString() = value
}

/**
 * Work that runs on a [Schedule] (AOS-501). A new job is one class registered in Koin as a
 * `ScheduledJob`; the scheduler needs no change (open/closed).
 *
 * Jobs must be **idempotent**: a slot is retried after a failure or a crash, so running it twice
 * must not double-send or double-count. Jobs read current state (e.g. "bills due in 3 days that have
 * no 3-day reminder yet") rather than "what changed since the last run".
 */
interface ScheduledJob {
    val name: JobName
    val schedule: Schedule

    /** A run longer than this is cancelled and recorded as failed (then retried per [retry]). */
    val timeout: Duration get() = Duration.ofMinutes(10)

    val retry: RetryPolicy get() = RetryPolicy.DEFAULT

    /** Does the work. Throwing marks the run failed; a [JobResult] with failures still counts as done. */
    suspend fun run(): JobResult
}

/** How a failed (or abandoned) slot is retried before the job waits for its next slot. */
data class RetryPolicy(val maxAttempts: Int, val delay: Duration) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1, was $maxAttempts" }
        require(!delay.isNegative) { "Retry delay can't be negative, was $delay" }
    }

    companion object {
        val DEFAULT = RetryPolicy(maxAttempts = 3, delay = Duration.ofMinutes(15))
    }
}

/**
 * What a run did, as named counts (e.g. `sent=2, failed=0`), stored with the run and logged.
 * Counts only: never amounts, names or other personal data.
 */
data class JobResult(val counts: Map<String, Int> = emptyMap()) {
    init {
        require(counts.values.all { it >= 0 }) { "Counts can't be negative: $counts" }
    }

    companion object {
        fun of(vararg counts: Pair<String, Int>) = JobResult(counts.toMap())
    }
}
