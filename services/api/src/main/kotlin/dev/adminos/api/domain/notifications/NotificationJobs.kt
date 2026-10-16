package dev.adminos.api.domain.notifications

import dev.adminos.api.domain.scheduling.JobName
import dev.adminos.api.domain.scheduling.JobResult
import dev.adminos.api.domain.scheduling.Schedule
import dev.adminos.api.domain.scheduling.ScheduledJob
import dev.adminos.api.domain.notifications.delivery.NotificationDispatcher
import dev.adminos.api.domain.scheduling.forEachIsolated
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

/**
 * Daily: first marks bills paid whose payment was recorded before the bill arrived (AOS-508), so
 * nobody is reminded of a paid bill; then marks overdue bills and sends reminders (AOS-501).
 */
class BillRemindersJob(
    private val engine: ReminderEngine,
    zone: ZoneId,
    private val bills: dev.adminos.api.domain.financial.BillRepository,
    private val billing: dev.adminos.api.domain.financial.BillTrackingService
) : ScheduledJob {
    override val name = JobName("bill-reminders")
    override val schedule = Schedule.Daily(LocalTime.of(9, 0), zone)

    override suspend fun run(): JobResult {
        val paidBySweep = billing.matchEarlierPayments((bills.findUpcoming(7) + bills.findOverdue()).distinctBy { it.id })
        val summary = engine.runReminders()
        return JobResult.of(
            "paidBySweep" to paidBySweep,
            "overdueMarked" to summary.overdueMarked,
            "remindersSent" to summary.remindersSent,
            "failed" to summary.failed
        )
    }
}

/** Daily: nudges users who stopped part-way through onboarding (AOS-501). */
class OnboardingNudgesJob(private val nudges: DropOffNudgeService, zone: ZoneId) : ScheduledJob {
    override val name = JobName("onboarding-nudges")
    override val schedule = Schedule.Daily(LocalTime.of(11, 0), zone)

    override suspend fun run(): JobResult {
        val summary = nudges.runNudges()
        return JobResult.of("usersChecked" to summary.usersChecked, "nudgesSent" to summary.nudgesSent, "failed" to summary.failed)
    }
}

/**
 * Every 5 minutes: delivers notifications that are due — held for quiet hours, waiting for a retry,
 * or left behind by a process that died mid-send (AOS-504). Up to [batchSize] per run.
 */
class PendingNotificationsJob(
    private val notifications: NotificationRepository,
    private val dispatcher: NotificationDispatcher,
    private val clock: Clock = Clock.systemUTC(),
    private val batchSize: Int = 500
) : ScheduledJob {
    private val logger = LoggerFactory.getLogger(PendingNotificationsJob::class.java)

    override val name = JobName("pending-notifications")
    override val schedule = Schedule.Every(Duration.ofMinutes(5))

    override suspend fun run(): JobResult {
        // The lease outlasts the job's timeout, so nothing claimed here is claimed twice.
        val due = notifications.claimDue(clock.instant(), lease = timeout.plusMinutes(5), limit = batchSize)
        val outcomes = mutableMapOf<NotificationStatus, Int>()
        val counts = forEachIsolated(due, logger, { "notification ${it.id}" }) { n ->
            val status = dispatcher.deliver(n).status
            outcomes[status] = (outcomes[status] ?: 0) + 1
        }
        return JobResult.of(
            "due" to due.size,
            "sent" to (outcomes[NotificationStatus.SENT] ?: 0),
            "retrying" to (outcomes[NotificationStatus.PENDING] ?: 0),
            "failed" to (outcomes[NotificationStatus.FAILED] ?: 0) + counts.failed
        )
    }
}
