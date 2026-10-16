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
 * Nightly: finds recurring payments in each active user's last 90 days (AOS-501). Detection
 * upserts by (user, merchant, cycle), so re-running a night is harmless.
 */
class SubscriptionDetectionJob(
    private val users: UserRepository,
    private val detector: SubscriptionDetectorService,
    zone: ZoneId
) : ScheduledJob {
    private val logger = LoggerFactory.getLogger(SubscriptionDetectionJob::class.java)

    override val name = JobName("subscription-detection")
    override val schedule = Schedule.Daily(LocalTime.of(4, 0), zone)

    override suspend fun run(): JobResult {
        val active = users.findActive()
        var found = 0
        val counts = forEachIsolated(active, logger, { "subscription detection for user ${it.id}" }) { user ->
            found += detector.detectSubscriptions(user.id).size
        }
        return JobResult.of("users" to active.size, "subscriptionsFound" to found, "failed" to counts.failed)
    }
}
