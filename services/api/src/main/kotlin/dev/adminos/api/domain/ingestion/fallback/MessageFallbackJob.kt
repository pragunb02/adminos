package dev.adminos.api.domain.ingestion.fallback

import dev.adminos.api.domain.scheduling.JobName
import dev.adminos.api.domain.scheduling.JobResult
import dev.adminos.api.domain.scheduling.RetryPolicy
import dev.adminos.api.domain.scheduling.Schedule
import dev.adminos.api.domain.scheduling.ScheduledJob
import java.time.Duration

/**
 * Every minute, reads what the regex intake queued for the fallback (AOS-705). A job rather than inline, so an
 * SMS upload never waits for a model. Each run reads at most [perRun] messages within [budget], so a slow local
 * model can't hold the scheduler; the rest wait for the next run. Registered only when AI_MESSAGES_ENABLED.
 */
class MessageFallbackJob(
    private val fallback: MessageFallback,
    private val perRun: Int = 20,
    private val budget: Duration = Duration.ofMinutes(4)
) : ScheduledJob {
    override val name = JobName("ai-message-reading")
    override val schedule = Schedule.Every(Duration.ofMinutes(1))
    override val timeout: Duration = Duration.ofMinutes(15) // the budget plus one slow call (OLLAMA_TIMEOUT_SECONDS ≤ 600)
    override val retry = RetryPolicy(maxAttempts = 1, delay = Duration.ZERO) // the next slot is the retry

    override suspend fun run(): JobResult = JobResult(fallback.drain(perRun, budget).asMap() + ("waiting" to fallback.queued))
}
