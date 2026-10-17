package dev.adminos.api.domain.agent.briefing

import dev.adminos.api.domain.identity.UserRepository
import dev.adminos.api.domain.notifications.NotificationPreferences
import dev.adminos.api.domain.notifications.NotificationPreferencesRepository
import dev.adminos.api.domain.scheduling.JobName
import dev.adminos.api.domain.scheduling.JobResult
import dev.adminos.api.domain.scheduling.Schedule
import dev.adminos.api.domain.scheduling.ScheduledJob
import dev.adminos.api.domain.scheduling.forEachIsolated
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Every 15 minutes: makes the weekly briefings that are due (AOS-506). Each user picks their own
 * day and time, so the job checks often and [WeeklyBriefingService] decides; it's idempotent.
 */
class WeeklyBriefingJob(
    private val users: UserRepository,
    private val preferences: NotificationPreferencesRepository,
    private val briefings: WeeklyBriefingService
) : ScheduledJob {
    private val logger = LoggerFactory.getLogger(WeeklyBriefingJob::class.java)

    override val name = JobName("weekly-briefing")
    override val schedule = Schedule.Every(Duration.ofMinutes(15))

    override suspend fun run(): JobResult {
        val active = users.findActive()
        var made = 0
        val counts = forEachIsolated(active, logger, { "weekly briefing for user ${it.id}" }) { user ->
            val prefs = preferences.findByUserId(user.id) ?: NotificationPreferences.defaultFor(user.id)
            if (briefings.briefIfDue(user, prefs) != null) made++
        }
        return JobResult.of("users" to active.size, "briefingsMade" to made, "failed" to counts.failed)
    }
}
