package dev.adminos.api.domain.financial

import dev.adminos.api.domain.identity.UserRepository
import dev.adminos.api.domain.notifications.PriceRiseAlerts
import dev.adminos.api.domain.scheduling.JobName
import dev.adminos.api.domain.scheduling.JobResult
import dev.adminos.api.domain.scheduling.Schedule
import dev.adminos.api.domain.scheduling.ScheduledJob
import dev.adminos.api.domain.scheduling.forEachIsolated
import org.slf4j.LoggerFactory
import java.time.LocalTime
import java.time.ZoneId

/**
 * Nightly: finds recurring payments in each active user's history (AOS-501, AOS-513), then tells them
 * about price rises it recorded (AOS-715). Detection upserts by (user, merchant, cycle) and a rise is
 * recorded once per (subscription, price), so re-running a night is harmless.
 */
class SubscriptionDetectionJob(
    private val users: UserRepository,
    private val detector: SubscriptionDetectorService,
    private val priceRiseAlerts: PriceRiseAlerts,
    zone: ZoneId
) : ScheduledJob {
    private val logger = LoggerFactory.getLogger(SubscriptionDetectionJob::class.java)

    override val name = JobName("subscription-detection")
    override val schedule = Schedule.Daily(LocalTime.of(4, 0), zone)

    override suspend fun run(): JobResult {
        val active = users.findActive()
        var found = 0
        var alerts = 0
        val counts = forEachIsolated(active, logger, { "subscription detection for user ${it.id}" }) { user ->
            found += detector.detectSubscriptions(user.id).size
            alerts += priceRiseAlerts.notifyPending(user.id).sent
        }
        return JobResult.of("users" to active.size, "subscriptionsFound" to found, "priceRiseAlerts" to alerts, "failed" to counts.failed)
    }
}
