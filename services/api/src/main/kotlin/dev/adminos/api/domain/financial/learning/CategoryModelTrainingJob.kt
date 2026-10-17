package dev.adminos.api.domain.financial.learning

import dev.adminos.api.domain.identity.UserRepository
import dev.adminos.api.domain.scheduling.JobName
import dev.adminos.api.domain.scheduling.JobResult
import dev.adminos.api.domain.scheduling.Schedule
import dev.adminos.api.domain.scheduling.ScheduledJob
import dev.adminos.api.domain.scheduling.forEachIsolated
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

/**
 * Every night, retrains each active user's learned categoriser and re-scores their recent Other rows (AOS-707, D-50).
 * Idempotent: training reads current state, and the re-scoring skips rows already holding this model's answer.
 * A failing user is logged and skipped. Registered only when learning from corrections is enabled.
 */
class CategoryModelTrainingJob(
    private val users: UserRepository,
    private val training: CategoryModelTrainingService,
    zone: ZoneId
) : ScheduledJob {
    private val logger = LoggerFactory.getLogger(CategoryModelTrainingJob::class.java)

    override val name = JobName("category-model-training")
    override val schedule = Schedule.Daily(LocalTime.of(3, 30), zone)
    override val timeout: Duration = Duration.ofMinutes(30)

    override suspend fun run(): JobResult {
        val active = users.findActive()
        val counts = mutableMapOf<String, Int>()
        val batch = forEachIsolated(active, logger, { "category model training for user ${it.id}" }) { user ->
            val report = training.retrain(user.id)
            counts.merge(countName(report.outcome), 1, Int::plus)
            report.backfill.forEach { (outcome, n) -> counts.merge(outcome.name.lowercase(), n, Int::plus) }
        }
        return JobResult(mapOf("users" to active.size, "failedUsers" to batch.failed) + counts)
    }

    private fun countName(outcome: TrainingOutcome) = when (outcome) {
        is TrainingOutcome.Trained -> "trained"
        is TrainingOutcome.ColdStart -> "coldStart"
        TrainingOutcome.Disabled -> "disabled"
    }
}
