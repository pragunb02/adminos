package dev.adminos.api.domain.documents

import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.domain.notifications.NotificationService
import dev.adminos.api.domain.notifications.NotificationType
import dev.adminos.api.domain.scheduling.JobName
import dev.adminos.api.domain.scheduling.JobResult
import dev.adminos.api.domain.scheduling.Schedule
import dev.adminos.api.domain.scheduling.ScheduledJob
import dev.adminos.api.domain.scheduling.forEachIsolated
import org.slf4j.LoggerFactory
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Daily at 09:30: reminds about documents expiring in 30, 7 and 1 days (a passport, insurance,
 * a PUC certificate…), each once per expiry date, on the user's own date (AOS-507).
 */
class DocumentExpiryJob(
    private val documents: DocumentRepository,
    private val notifications: NotificationService,
    private val calendar: UserCalendar,
    zone: ZoneId
) : ScheduledJob {
    private val logger = LoggerFactory.getLogger(DocumentExpiryJob::class.java)

    override val name = JobName("document-expiry")
    override val schedule = Schedule.Daily(LocalTime.of(9, 30), zone)

    override suspend fun run(): JobResult {
        val today = calendar.serverToday()
        val expiring = documents.findExpiringBetween(today.minusDays(1), today.plusDays(DAYS.max() + 1L))
        var sent = 0
        val counts = forEachIsolated(expiring, logger, { "expiry reminder for document ${it.id}" }) { d ->
            if (remind(d)) sent++
        }
        return JobResult.of("remindersSent" to sent, "failed" to counts.failed)
    }

    internal suspend fun remind(d: Document): Boolean {
        val expiresOn = d.expiresOn ?: return false
        val days = ChronoUnit.DAYS.between(calendar.today(d.userId), expiresOn).toInt()
        if (days !in DAYS || days in d.expiryRemindersSent) return false
        notifications.send(
            userId = d.userId,
            type = NotificationType.GENERAL,
            title = "${d.title} expires in $days day${if (days == 1) "" else "s"}",
            body = "It expires on $expiresOn. Time to renew it.",
            data = mapOf("entity_type" to "document", "entity_id" to d.id.toString(), "action" to "view_document"),
            userTimezone = calendar.zoneOf(d.userId).id
        )
        documents.update(d.copy(expiryRemindersSent = d.expiryRemindersSent + days))
        return true
    }

    companion object {
        val DAYS = setOf(30, 7, 1)
    }
}
