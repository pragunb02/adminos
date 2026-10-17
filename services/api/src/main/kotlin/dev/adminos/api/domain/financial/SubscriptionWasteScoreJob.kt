package dev.adminos.api.domain.financial

import dev.adminos.api.domain.identity.UserRepository
import dev.adminos.api.domain.scheduling.JobName
import dev.adminos.api.domain.scheduling.JobResult
import dev.adminos.api.domain.scheduling.Schedule
import dev.adminos.api.domain.scheduling.ScheduledJob
import dev.adminos.api.domain.scheduling.forEachIsolated
import org.slf4j.LoggerFactory
import java.time.LocalTime
import java.time.ZoneId

/**
 * Daily at 05:00, an hour after subscription detection (04:00): scores every active user's
 * subscriptions for waste (AOS-509). Scores come from current state, so re-running a day is harmless.
 */
class SubscriptionWasteScoreJob(
    private val users: UserRepository,
    private val scorer: WasteScoreService,
    zone: ZoneId
) : ScheduledJob {
    private val logger = LoggerFactory.getLogger(SubscriptionWasteScoreJob::class.java)

    override val name = JobName("subscription-waste-score")
    override val schedule = Schedule.Daily(LocalTime.of(5, 0), zone)

    override suspend fun run(): JobResult {
        val active = users.findActive()
        var scored = 0
        var flagged = 0
        var failedSubscriptions = 0
        val counts = forEachIsolated(active, logger, { "waste scoring for user ${it.id}" }) { user ->
            val run = scorer.scoreUser(user.id)
            scored += run.scored
            flagged += run.flagged
            failedSubscriptions += run.failed
        }
        return JobResult.of(
            "users" to active.size, "scored" to scored, "flagged" to flagged,
            "failedUsers" to counts.failed, "failedSubscriptions" to failedSubscriptions
        )
    }
}
