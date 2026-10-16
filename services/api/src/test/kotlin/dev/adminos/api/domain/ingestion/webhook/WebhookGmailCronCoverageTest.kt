package dev.adminos.api.domain.ingestion.webhook

import dev.adminos.api.domain.ingestion.connection.ConnectionRepository
import dev.adminos.api.domain.ingestion.connection.ConnectionService
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
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/** AOS-312: the daily Gmail fallback sync. */
class WebhookGmailCronCoverageTest {

    private val connections = InMemoryConnectionRepository()
    private val syncSessions = InMemorySyncSessionRepository()

    private fun cron(
        connectionRepo: ConnectionRepository = connections,
        syncRepo: SyncSessionRepository = syncSessions
    ) = GmailCronService(ConnectionService(connectionRepo, syncRepo), IngestionService(IngestEventService(InMemoryIngestEventRepository()), syncRepo))

    private fun gmail(tokenExpiresAt: Instant? = null, status: ConnectionStatus = ConnectionStatus.CONNECTED, source: SourceType = SourceType.GMAIL) = runBlocking {
        connections.save(UserConnection(userId = UUID.randomUUID(), sourceType = source, status = status, gmailAddress = "u-${UUID.randomUUID()}@gmail.com",
            historyId = "9876", tokenExpiresAt = tokenExpiresAt))
    }

    @Test
    fun `with no gmail connections nothing is enqueued`() = runBlocking<Unit> {
        assertEquals(DailySyncResult(0, 0, 0, 0), cron().runDailyFallbackSync())
    }

    @Test
    fun `every connected gmail connection gets an incremental sync from its history id`() = runBlocking<Unit> {
        val conn = gmail()

        val result = cron().runDailyFallbackSync()

        assertEquals(DailySyncResult(1, 1, 0, 0), result)
        assertEquals(conn.id, syncSessions.findByUserId(conn.userId).single().connectionId)
    }

    @Test
    fun `sms and disconnected connections are not synced`() = runBlocking<Unit> {
        gmail(status = ConnectionStatus.DISCONNECTED)
        gmail(source = SourceType.SMS)

        assertEquals(0, cron().runDailyFallbackSync().totalConnections)
    }

    @Test
    fun `a connection whose token is still valid is enqueued`() = runBlocking<Unit> {
        gmail(tokenExpiresAt = Instant.now().plusSeconds(3600))

        assertEquals(1, cron().runDailyFallbackSync().enqueued)
    }

    @Test
    fun `a connection with an expired token is disconnected and counted as an error`() = runBlocking<Unit> {
        val conn = gmail(tokenExpiresAt = Instant.now().minusSeconds(60))

        val result = cron().runDailyFallbackSync()

        assertEquals(DailySyncResult(1, 0, 0, 1), result)
        assertEquals(ConnectionStatus.DISCONNECTED, connections.findById(conn.id)?.status)
        assertTrue(syncSessions.findByUserId(conn.userId).isEmpty())
    }

    @Test
    fun `a failure to disconnect an expired connection is contained`() = runBlocking<Unit> {
        gmail(tokenExpiresAt = Instant.now().minusSeconds(60))
        // The listing still returns the connection, but it vanished before it could be disconnected.
        val vanishing = object : ConnectionRepository by connections {
            override suspend fun findById(id: UUID): UserConnection? = null
        }

        assertEquals(DailySyncResult(1, 0, 0, 1), cron(connectionRepo = vanishing).runDailyFallbackSync())
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
        assertEquals(ok.id, syncSessions.findByUserId(ok.userId).single().connectionId)
    }

    @Test
    fun `more connections than the concurrency bound are all enqueued`() = runBlocking<Unit> {
        repeat(25) { gmail() }

        assertEquals(25, cron().runDailyFallbackSync().enqueued)
    }
}
