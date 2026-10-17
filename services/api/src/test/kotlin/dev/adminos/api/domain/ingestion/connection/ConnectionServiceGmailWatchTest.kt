package dev.adminos.api.domain.ingestion.connection

import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.sync.InMemorySyncSessionRepository
import dev.adminos.api.domain.ingestion.sync.IngestionService
import dev.adminos.api.domain.ingestion.sync.SyncType
import dev.adminos.api.domain.scheduling.TestClock
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/** AOS-405: connecting Gmail registers push after the first sync is queued; disconnecting stops it. */
class ConnectionServiceGmailWatchTest {

    private val now = Instant.parse("2026-10-17T06:00:00Z")
    private val clock = TestClock(now)
    private val connections = InMemoryConnectionRepository()
    private val syncSessions = InMemorySyncSessionRepository()
    private val encryptor = randomTokenEncryptor()
    private val api = FakeGmailPushApi(expiresAt = now.plus(Duration.ofDays(7)))
    private val refresher = FakeTokenRefresher { clock.instant().plusSeconds(3599) }
    private val watches = GmailWatchService(
        connections, api, GmailAccessTokens(connections, refresher, encryptor, clock),
        GmailWatchSettings("projects/adminos-dev/topics/gmail-push", clock = clock)
    )
    private val ingestion = IngestionService(IngestEventService(InMemoryIngestEventRepository()), syncSessions)
    private val service = ConnectionService(connections, syncSessions, encryptor, ingestion, watches)
    private val userId = UUID.randomUUID()
    private val access = runtimeToken()

    private fun grant() = OAuthGrant.fromGoogle(access, runtimeToken("1//"), 3599, "openid email $GMAIL_READONLY_SCOPE", now)

    @Test
    fun `connecting gmail queues the first sync and then registers push`() = runBlocking<Unit> {
        val conn = service.connectGmail(userId, "pragun.b@gmail.com", grant())

        assertEquals(listOf(SyncType.HISTORICAL), syncSessions.findByUserId(userId).map { it.syncType })
        assertEquals(listOf(access), api.watchTokens.toList(), "the token Google just granted, decrypted")
        assertEquals(now.plus(Duration.ofDays(7)), conn.watch.expiresAt)
        assertEquals(now.plus(Duration.ofDays(7)), connections.findById(conn.id)?.watch?.expiresAt)
        assertEquals(now.plus(Duration.ofDays(7)).toString(), ConnectionResponse.from(conn).watchExpiresAt)
    }

    @Test
    fun `a failed push registration never fails the connect`() = runBlocking<Unit> {
        api.watchFailures += GmailPushException.Rejected(403)

        val conn = service.connectGmail(userId, "pragun.b@gmail.com", grant())

        assertEquals(ConnectionStatus.CONNECTED, conn.status)
        assertEquals(1, syncSessions.findByUserId(userId).size)
        assertEquals("Gmail push: Gmail answered HTTP 403", connections.findById(conn.id)?.watch?.error)
        assertNull(ConnectionResponse.from(conn).watchExpiresAt)
    }

    @Test
    fun `connecting an already connected gmail does not register again`() = runBlocking<Unit> {
        service.connectGmail(userId, "pragun.b@gmail.com", grant())

        service.connectGmail(userId, "pragun.b@gmail.com", grant())

        assertEquals(1, api.watchTokens.size)
    }

    @Test
    fun `disconnecting stops push with the stored token before wiping it, and forgets the watch`() = runBlocking<Unit> {
        val conn = service.connectGmail(userId, "pragun.b@gmail.com", grant())

        val disconnected = service.disconnect(userId, conn.id)

        assertEquals(listOf(access), api.stopTokens.toList())
        assertEquals(GmailWatch.NONE, disconnected.watch)
        val stored = assertNotNull(connections.findById(conn.id))
        assertNull(stored.accessToken)
        assertEquals(GmailWatch.NONE, stored.watch)
    }

    @Test
    fun `a failed stop never blocks the disconnect`() = runBlocking<Unit> {
        val conn = service.connectGmail(userId, "pragun.b@gmail.com", grant())
        api.stopFailure = GmailPushException.Unavailable(java.io.IOException("reset"))

        val disconnected = service.disconnect(userId, conn.id)

        assertEquals(ConnectionStatus.DISCONNECTED, disconnected.status)
        assertNull(disconnected.refreshToken)
    }

    @Test
    fun `reconnecting starts a fresh watch`() = runBlocking<Unit> {
        val first = service.connectGmail(userId, "pragun.b@gmail.com", grant())
        service.disconnect(userId, first.id)
        api.expiresAt = now.plus(Duration.ofDays(6))

        val again = service.connectGmail(userId, "pragun.b@gmail.com", grant())

        assertEquals(first.id, again.id)
        assertEquals(2, api.watchTokens.size)
        assertEquals(now.plus(Duration.ofDays(6)), connections.findById(again.id)?.watch?.expiresAt)
    }
}
