package dev.adminos.api.domain.ingestion.webhook

import dev.adminos.api.domain.ingestion.connection.ConnectionService
import dev.adminos.api.domain.ingestion.connection.SourceType
import dev.adminos.api.domain.ingestion.sync.IngestionService
import dev.adminos.api.domain.ingestion.sync.SyncSessionRepository
import dev.adminos.api.domain.ingestion.sync.SyncType
import dev.adminos.api.domain.scheduling.JobName
import dev.adminos.api.domain.scheduling.JobResult
import dev.adminos.api.domain.scheduling.Schedule
import dev.adminos.api.domain.scheduling.ScheduledJob
import dev.adminos.api.domain.scheduling.forEachIsolated
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

/**
 * The daily Gmail fallback sync (AOS-401, D-59): Gmail push can be missed or not set up, so once a
 * day every connected Gmail connection gets a sync — **unless** a push or manual sync of it
 * completed in the last [skipWindow] (the skip rule). The fallback's own syncs don't count, so a
 * connection without push is still synced every day.
 *
 * The worker starts each sync from the history ID stored on the connection (incremental) or does
 * a bounded full sync; this job only queues. It never disconnects anything: access tokens expire
 * hourly by design and the worker refreshes them.
 */
class GmailCronService(
    private val connectionService: ConnectionService,
    private val ingestionService: IngestionService,
    private val syncSessions: SyncSessionRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val skipWindow: Duration = Duration.ofHours(24)
) {
    private val logger = LoggerFactory.getLogger(GmailCronService::class.java)

    suspend fun runDailyFallbackSync(): DailySyncResult {
        val connections = connectionService.findConnectedBySourceType(SourceType.GMAIL)
        val recentSince = clock.instant().minus(skipWindow)
        var enqueued = 0
        var skipped = 0
        val counts = forEachIsolated(connections, logger, { "gmail connection ${it.id}" }) { connection ->
            val lastSynced = syncSessions.lastCompletedAt(connection.id, COUNTS_AS_RECENT)
            if (lastSynced != null && lastSynced > recentSince) {
                skipped++
            } else {
                ingestionService.enqueueGmailIngest(connection.userId, connection.id, syncType = SyncType.CRON_FALLBACK)
                enqueued++
            }
        }
        val result = DailySyncResult(totalConnections = connections.size, enqueued = enqueued, skipped = skipped, errors = counts.failed)
        logger.info("Gmail fallback sync: connections={}, enqueued={}, skipped={}, errors={}",
            result.totalConnections, result.enqueued, result.skipped, result.errors)
        return result
    }

    companion object {
        /** Push (incremental, or historical without a history ID) and manual syncs; not the fallback's own. */
        val COUNTS_AS_RECENT = setOf(SyncType.INCREMENTAL, SyncType.HISTORICAL, SyncType.MANUAL)
    }
}

data class DailySyncResult(val totalConnections: Int, val enqueued: Int, val skipped: Int, val errors: Int)

/** Daily at 06:00 in the scheduler's zone, before the 09:00 bill reminders read what it found (D-50). */
class GmailFallbackSyncJob(private val cron: GmailCronService, zone: ZoneId) : ScheduledJob {
    override val name = JobName("gmail-fallback-sync")
    override val schedule = Schedule.Daily(LocalTime.of(6, 0), zone)

    override suspend fun run(): JobResult {
        val r = cron.runDailyFallbackSync()
        return JobResult.of("connections" to r.totalConnections, "enqueued" to r.enqueued, "skipped" to r.skipped, "failed" to r.errors)
    }
}
