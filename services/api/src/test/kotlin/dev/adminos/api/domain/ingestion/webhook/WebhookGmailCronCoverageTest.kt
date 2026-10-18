package dev.adminos.api.domain.ingestion.webhook

import dev.adminos.api.domain.ingestion.connection.ConnectionService
import dev.adminos.api.domain.ingestion.connection.randomTokenEncryptor
import dev.adminos.api.domain.ingestion.connection.ConnectionStatus
import dev.adminos.api.domain.ingestion.connection.InMemoryConnectionRepository
import dev.adminos.api.domain.ingestion.connection.SourceType
import dev.adminos.api.domain.ingestion.connection.UserConnection
import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.sync.InMemorySyncSessionRepository
import dev.adminos.api.domain.ingestion.sync.IngestionService
import dev.adminos.api.domain.ingestion.sync.SyncSession
import dev.adminos.api.domain.ingestion.sync.SyncSessionRepository
import dev.adminos.api.domain.ingestion.sync.SyncSessionStatus
import dev.adminos.api.domain.ingestion.sync.SyncType
import dev.adminos.api.domain.scheduling.Schedule
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

/** AOS-401: the daily Gmail fallback sync and its skip rule (D-59). */
class WebhookGmailCronCoverageTest {

    private val now = Instant.parse("2026-10-17T00:30:00Z") // 06:00 in India
    private val connections = InMemoryConnectionRepository()
    private val syncSessions = InMemorySyncSessionRepository()

    private fun cron(syncRepo: SyncSessionRepository = syncSessions) = GmailCronService(
        ConnectionService(connections, syncRepo, randomTokenEncryptor()),
        IngestionService(IngestEventService(InMemoryIngestEventRepository()), syncRepo),
        syncRepo,
        Clock.fixed(now, ZoneOffset.UTC)
    )

    private fun gmail(status: ConnectionStatus = ConnectionStatus.CONNECTED, source: SourceType = SourceType.GMAIL, tokenExpiresAt: Instant? = null) = runBlocking {
        connections.save(UserConnection(userId = UUID.randomUUID(), sourceType = source, status = status, gmailAddress = "u-${UUID.randomUUID()}@gmail.com",
            historyId = "9876", tokenExpiresAt = tokenExpiresAt))
    }

    private fun synced(conn: UserConnection, type: SyncType, ago: Duration, status: SyncSessionStatus = SyncSessionStatus.COMPLETED) = runBlocking {
        syncSessions.save(SyncSession(userId = conn.userId, connectionId = conn.id, syncType = type, status = status, completedAt = now.minus(ago)))
    }

    private fun queued(conn: UserConnection) = runBlocking { syncSessions.findByUserId(conn.userId).filter { it.status == SyncSessionStatus.QUEUED } }

    @Test
    fun `with no gmail connections nothing is enqueued`() = runBlocking<Unit> {
        assertEquals(DailySyncResult(0, 0, 0, 0), cron().runDailyFallbackSync())
    }

    @Test
    fun `a connection with no recent sync gets a fallback sync`() = runBlocking<Unit> {
        val conn = gmail()

        val result = cron().runDailyFallbackSync()

        assertEquals(DailySyncResult(1, 1, 0, 0), result)
        val session = queued(conn).single()
        assertEquals(SyncType.CRON_FALLBACK, session.syncType)
        assertEquals(conn.id, session.connectionId)
    }

    @Test
    fun `a connection synced by push in the last 24 hours is skipped`() = runBlocking<Unit> {
        val conn = gmail()
        synced(conn, SyncType.INCREMENTAL, Duration.ofHours(23))

        assertEquals(DailySyncResult(1, 0, 1, 0), cron().runDailyFallbackSync())
        assertTrue(queued(conn).isEmpty())
    }

    @Test
    fun `a connection synced manually in the last 24 hours is skipped`() = runBlocking<Unit> {
        synced(gmail(), SyncType.MANUAL, Duration.ofMinutes(5))

        assertEquals(1, cron().runDailyFallbackSync().skipped)
    }

    @Test
    fun `a first full sync in the last 24 hours also counts`() = runBlocking<Unit> {
        synced(gmail(), SyncType.HISTORICAL, Duration.ofHours(2))

        assertEquals(1, cron().runDailyFallbackSync().skipped)
    }

    @Test
    fun `a success just over 24 hours ago does not skip`() = runBlocking<Unit> {
        synced(gmail(), SyncType.INCREMENTAL, Duration.ofHours(24).plusSeconds(1))

        assertEquals(1, cron().runDailyFallbackSync().enqueued)
    }

    @Test
    fun `a success exactly 24 hours ago does not skip`() = runBlocking<Unit> {
        synced(gmail(), SyncType.MANUAL, Duration.ofHours(24))

        assertEquals(1, cron().runDailyFallbackSync().enqueued)
    }

    @Test
    fun `the fallback's own syncs never skip the next day's fallback`() = runBlocking<Unit> {
        // Without push, yesterday's 06:00 fallback finished 23h55m ago: today's must still run.
        synced(gmail(), SyncType.CRON_FALLBACK, Duration.ofHours(23).plusMinutes(55))

        assertEquals(1, cron().runDailyFallbackSync().enqueued)
    }

    @Test
    fun `a recent failed sync does not skip`() = runBlocking<Unit> {
        synced(gmail(), SyncType.INCREMENTAL, Duration.ofHours(1), status = SyncSessionStatus.FAILED)

        assertEquals(1, cron().runDailyFallbackSync().enqueued)
    }

    @Test
    fun `sms and disconnected connections are not synced`() = runBlocking<Unit> {
        gmail(status = ConnectionStatus.DISCONNECTED)
        gmail(status = ConnectionStatus.ERROR)
        gmail(source = SourceType.SMS)

        assertEquals(0, cron().runDailyFallbackSync().totalConnections)
    }

    @Test
    fun `an expired access token is synced, not disconnected`() = runBlocking<Unit> {
        // Access tokens expire hourly; the worker refreshes them. The old cron disconnected these.
        val conn = gmail(tokenExpiresAt = now.minusSeconds(60))

        assertEquals(DailySyncResult(1, 1, 0, 0), cron().runDailyFallbackSync())
        assertEquals(ConnectionStatus.CONNECTED, connections.findById(conn.id)?.status)
    }

    @Test
    fun `a failure to enqueue one connection is counted and the others still run`() = runBlocking<Unit> {
        val failing = gmail()
        val ok = gmail()
        val flaky = object : SyncSessionRepository by syncSessions {
            override suspend fun save(session: SyncSession): SyncSession =
                if (session.connectionId == failing.id) error("redis down") else syncSessions.save(session)
        }

        val result = cron(syncRepo = flaky).runDailyFallbackSync()

        assertEquals(DailySyncResult(2, 1, 0, 1), result)
        assertEquals(ok.id, queued(ok).single().connectionId)
    }

    @Test
    fun `the scheduled job is gmail-fallback-sync daily at 06 00 and reports its counts`() = runBlocking<Unit> {
        gmail()
        synced(gmail(), SyncType.MANUAL, Duration.ofHours(1))
        val zone = ZoneId.of("Asia/Kolkata")
        val job = GmailFallbackSyncJob(cron(), zone)

        val result = job.run()

        assertEquals("gmail-fallback-sync", job.name.value)
        assertEquals(Schedule.Daily(LocalTime.of(6, 0), zone), job.schedule)
        assertEquals(mapOf("connections" to 2, "enqueued" to 1, "skipped" to 1, "failed" to 0), result.counts)
    }
}
