package dev.adminos.api.domain.financial.ai

import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionRepository
import dev.adminos.api.domain.identity.UserRepository
import dev.adminos.api.domain.scheduling.JobName
import dev.adminos.api.domain.scheduling.JobResult
import dev.adminos.api.domain.scheduling.RetryPolicy
import dev.adminos.api.domain.scheduling.Schedule
import dev.adminos.api.domain.scheduling.ScheduledJob
import dev.adminos.api.domain.scheduling.forEachIsolated
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/**
 * Every 15 minutes, asks the AI fallback about recent transactions the rules left as Other (AOS-512, D-62).
 * A batch job rather than an ingestion step, so a slow or failing model never slows or fails ingestion.
 * Idempotent: done transactions carry `metadata.ai`, and answers are cached per merchant.
 * Registered only when AI categorization is enabled.
 */
class AiCategorizationJob(
    private val users: UserRepository,
    private val transactions: TransactionRepository,
    private val service: AiCategorizationService,
    private val clock: Clock = Clock.systemUTC(),
    private val lookBack: Duration = Duration.ofDays(30),
    private val perUser: Int = 200
) : ScheduledJob {
    private val logger = LoggerFactory.getLogger(AiCategorizationJob::class.java)

    override val name = JobName("ai-categorization")
    override val schedule = Schedule.Every(Duration.ofMinutes(15))
    override val retry = RetryPolicy(maxAttempts = 1, delay = Duration.ZERO) // the next slot is the retry

    override suspend fun run(): JobResult {
        val active = users.findActive()
        val outcomes = AiOutcome.entries.associateWith { 0 }.toMutableMap()
        val since = clock.instant().minus(lookBack)
        val counts = forEachIsolated(active, logger, { "AI categorization for user ${it.id}" }) { user ->
            val others = transactions.findByUserId(user.id, from = since, category = TransactionCategory.OTHER, limit = perUser)
            for (t in others.filter { service.isCandidate(it) }) {
                val outcome = service.categorize(t)
                outcomes[outcome] = outcomes.getValue(outcome) + 1
            }
        }
        return JobResult(mapOf("users" to active.size, "failedUsers" to counts.failed) +
            outcomes.mapKeys { (k, _) -> k.name.lowercase() })
    }
}
