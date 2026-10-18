package dev.adminos.api.domain.ingestion.webhook

import dev.adminos.api.domain.ingestion.connection.ConnectionService
import dev.adminos.api.domain.ingestion.connection.ConnectionStatus
import dev.adminos.api.domain.ingestion.connection.InMemoryConnectionRepository
import dev.adminos.api.domain.ingestion.connection.SourceType
import dev.adminos.api.domain.ingestion.connection.UserConnection
import dev.adminos.api.domain.ingestion.connection.randomTokenEncryptor
import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.sync.InMemorySyncSessionRepository
import dev.adminos.api.domain.ingestion.sync.IngestionService
import dev.adminos.api.domain.ingestion.sync.SyncSession
import dev.adminos.api.domain.ingestion.sync.SyncSessionStatus
import dev.adminos.api.domain.ingestion.sync.SyncType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** AOS-630 (AUD-27): a replayed or repeated Gmail push can't multiply syncs, and no new mail is left behind. */
class GmailPushServiceTest {
    private val connectionRepo = InMemoryConnectionRepository()
    private val sessions = InMemorySyncSessionRepository()
    private val connections = ConnectionService(connectionRepo, sessions, randomTokenEncryptor())
    private val ingestion = IngestionService(IngestEventService(InMemoryIngestEventRepository()), sessions)
    private val service = GmailPushService(connections, ingestion)
    private val userId = UUID.randomUUID()
    private val address = "owner@example.com"

    private fun gmail(historyId: String? = null): UserConnection = runBlocking {
        val c = connections.createConnection(userId, SourceType.GMAIL, address)
        if (historyId == null) c else connectionRepo.update(c.copy(historyId = historyId))
    }

    private suspend fun gmailSessions() = sessions.findByUserId(userId, limit = 1000)

    private suspend fun worker(session: SyncSession, status: SyncSessionStatus) = sessions.update(session.copy(status = status))

    @Test
    fun `the first push queues an incremental sync`() = runBlocking<Unit> {
        gmail()
        val outcome = assertIs<GmailPushOutcome.Queued>(service.onPush(address, 1001))
        assertEquals(SyncType.INCREMENTAL, outcome.session.syncType)
        assertEquals(1, gmailSessions().size)
    }

    @Test
    fun `a hundred replays of a push while its sync is queued add no sync`() = runBlocking<Unit> {
        gmail()
        val first = assertIs<GmailPushOutcome.Queued>(service.onPush(address, 1001)).session
        repeat(100) {
            assertEquals(GmailPushOutcome.Coalesced(first.id), service.onPush(address, 1001))
        }
        assertEquals(1, gmailSessions().size)
    }

    @Test
    fun `a replay with a forged larger history ID still can't add a second waiting sync`() = runBlocking<Unit> {
        gmail("1000")
        val first = assertIs<GmailPushOutcome.Queued>(service.onPush(address, 1001)).session
        assertEquals(GmailPushOutcome.Coalesced(first.id), service.onPush(address, Long.MAX_VALUE))
        assertEquals(1, gmailSessions().size)
    }

    @Test
    fun `concurrent pushes queue exactly one sync`() = runBlocking<Unit> {
        gmail()
        val outcomes = (1..50).map { async(Dispatchers.Default) { service.onPush(address, 2000L + it) } }.awaitAll()
        assertEquals(1, outcomes.count { it is GmailPushOutcome.Queued })
        assertEquals(1, gmailSessions().size)
    }

    @Test
    fun `a push during a running sync queues the next one, so mail that arrives meanwhile is read`() = runBlocking<Unit> {
        gmail()
        val running = assertIs<GmailPushOutcome.Queued>(service.onPush(address, 1001)).session
        worker(running, SyncSessionStatus.IN_PROGRESS)

        val next = assertIs<GmailPushOutcome.Queued>(service.onPush(address, 1002)).session
        assertEquals(GmailPushOutcome.Coalesced(next.id), service.onPush(address, 1003), "and only one waits")
        assertEquals(2, gmailSessions().size)
    }

    @Test
    fun `a push at or before the stored history ID is already synced`() = runBlocking<Unit> {
        gmail("5000")
        assertEquals(GmailPushOutcome.AlreadySynced, service.onPush(address, 5000))
        assertEquals(GmailPushOutcome.AlreadySynced, service.onPush(address, 4999))
        assertEquals(0, gmailSessions().size)
        assertIs<GmailPushOutcome.Queued>(service.onPush(address, 5001))
    }

    @Test
    fun `a stored history ID that isn't a number never suppresses a push`() = runBlocking<Unit> {
        gmail("history_1760000000")
        assertIs<GmailPushOutcome.Queued>(service.onPush(address, 1))
    }

    @Test
    fun `a push without a history ID is still debounced`() = runBlocking<Unit> {
        gmail("5000")
        val first = assertIs<GmailPushOutcome.Queued>(service.onPush(address, null)).session
        assertEquals(GmailPushOutcome.Coalesced(first.id), service.onPush(address, null))
    }

    @Test
    fun `after a sync completes or fails a new push queues again`() = runBlocking<Unit> {
        gmail()
        val first = assertIs<GmailPushOutcome.Queued>(service.onPush(address, 1001)).session
        worker(first, SyncSessionStatus.COMPLETED)
        val second = assertIs<GmailPushOutcome.Queued>(service.onPush(address, 1002)).session
        worker(second, SyncSessionStatus.FAILED)
        assertIs<GmailPushOutcome.Queued>(service.onPush(address, 1003))
    }

    @Test
    fun `a queued sync older than the window is treated as lost`() = runBlocking<Unit> {
        val connection = gmail()
        val old = Instant.now().minus(GmailPushService.PENDING_WINDOW).minusSeconds(60)
        sessions.save(SyncSession(userId = userId, connectionId = connection.id, syncType = SyncType.INCREMENTAL, createdAt = old, updatedAt = old))
        assertIs<GmailPushOutcome.Queued>(service.onPush(address, 1001))
    }

    @Test
    fun `any queued sync of the connection absorbs a push, a manual one too`() = runBlocking<Unit> {
        val connection = gmail()
        val manual = ingestion.enqueueGmailIngest(userId, connection.id, syncType = SyncType.MANUAL)
        assertEquals(GmailPushOutcome.Coalesced(manual.id), service.onPush(address, 1001))
    }

    @Test
    fun `pushes for an unknown or inactive connection queue nothing`() = runBlocking<Unit> {
        assertEquals(GmailPushOutcome.NoConnection, service.onPush("stranger@example.com", 1))
        val c = gmail()
        connectionRepo.update(c.copy(status = ConnectionStatus.DISCONNECTED))
        assertEquals(GmailPushOutcome.NotActive, service.onPush(address, 1))
        assertEquals(0, gmailSessions().size)
    }
}
