package dev.adminos.api.domain.notifications

import dev.adminos.api.domain.scheduling.JobName
import dev.adminos.api.domain.scheduling.JobResult
import dev.adminos.api.domain.scheduling.Schedule
import dev.adminos.api.domain.scheduling.ScheduledJob
import java.time.LocalTime
import java.time.ZoneId

/** Daily: marks overdue bills and sends bill reminders on the user's chosen days (AOS-501). */
class BillRemindersJob(private val engine: ReminderEngine, zone: ZoneId) : ScheduledJob {
    override val name = JobName("bill-reminders")
    override val schedule = Schedule.Daily(LocalTime.of(9, 0), zone)

    override suspend fun run(): JobResult {
        val summary = engine.runReminders()
        return JobResult.of(
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
