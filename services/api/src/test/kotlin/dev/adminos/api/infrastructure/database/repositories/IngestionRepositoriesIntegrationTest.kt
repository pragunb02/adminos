package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.ingestion.connection.ConnectionStatus
import dev.adminos.api.domain.ingestion.connection.SourceType
import dev.adminos.api.domain.ingestion.connection.UserConnection
import dev.adminos.api.domain.ingestion.sync.SyncSession
import dev.adminos.api.domain.ingestion.sync.SyncSessionStatus
import dev.adminos.api.domain.ingestion.sync.SyncType
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestionRepositoriesIntegrationTest {

    private val connections = ExposedConnectionRepository()
    private val syncSessions = ExposedSyncSessionRepository()

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private suspend fun newConnection(userId: UUID, source: SourceType = SourceType.GMAIL) =
        connections.save(
            UserConnection(
                userId = userId,
                sourceType = source,
                status = ConnectionStatus.CONNECTED,
                oauthScope = listOf("openid", "email", "https://www.googleapis.com/auth/gmail.readonly"),
                gmailAddress = "me-${UUID.randomUUID()}@gmail.com",
                historyId = "12345",
                totalSynced = 3
            )
        )

    @Test
    fun `connection round-trips including oauth scope list`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val saved = newConnection(userId)

        val found = assertNotNull(connections.findById(saved.id))
        assertEquals(SourceType.GMAIL, found.sourceType)
        assertEquals(ConnectionStatus.CONNECTED, found.status)
        assertEquals(saved.oauthScope, found.oauthScope)
        assertEquals(saved.gmailAddress, found.gmailAddress)
        assertEquals(saved.id, connections.findByGmailAddress(saved.gmailAddress!!)?.id)
        assertEquals(saved.id, connections.findByUserAndSource(userId, SourceType.GMAIL)?.id)
        assertEquals(3, found.totalSynced)

        connections.update(found.copy(status = ConnectionStatus.ERROR, lastError = "token expired"))
        val updated = assertNotNull(connections.findById(saved.id))
        assertEquals(ConnectionStatus.ERROR, updated.status)
        assertEquals("token expired", updated.lastError)
    }

    @Test
    fun `every source type and connection status persists`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        // uq_conn_source allows one connection per (user, source)
        for (source in SourceType.entries) {
            val c = newConnection(userId, source)
            for (status in ConnectionStatus.entries) {
                connections.update(c.copy(status = status))
                assertEquals(status, connections.findById(c.id)?.status, "status $status for $source")
            }
        }
    }

    @Test
    fun `sync session round-trips every type and status`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val connection = newConnection(userId)
        for (type in SyncType.entries) {
            val session = syncSessions.save(SyncSession(userId = userId, connectionId = connection.id, syncType = type, totalItems = 10))
            for (status in SyncSessionStatus.entries) {
                syncSessions.update(session.copy(status = status, processedItems = 7, failedItems = 1, duplicateItems = 2))
                val found = assertNotNull(syncSessions.findById(session.id))
                assertEquals(type, found.syncType)
                assertEquals(status, found.status)
                assertEquals(7, found.processedItems)
            }
        }
        assertEquals(SyncType.entries.size, syncSessions.findByUserId(userId).size)
    }

    @Test
    fun `last completed time counts only completed sessions of the given types for that connection`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val connection = newConnection(userId)
        val other = newConnection(PostgresTestDatabase.newUser())
        val at = { h: Long -> java.time.Instant.parse("2026-10-16T00:00:00Z").plusSeconds(h * 3600) }
        suspend fun session(conn: UserConnection, type: SyncType, status: SyncSessionStatus, completed: java.time.Instant?) =
            syncSessions.save(SyncSession(userId = conn.userId, connectionId = conn.id, syncType = type, status = status, completedAt = completed))
        session(connection, SyncType.MANUAL, SyncSessionStatus.COMPLETED, at(1))
        session(connection, SyncType.INCREMENTAL, SyncSessionStatus.COMPLETED, at(5))
        session(connection, SyncType.INCREMENTAL, SyncSessionStatus.FAILED, at(9))          // not completed
        session(connection, SyncType.CRON_FALLBACK, SyncSessionStatus.COMPLETED, at(12))    // not asked for
        session(other, SyncType.MANUAL, SyncSessionStatus.COMPLETED, at(20))                // another connection

        assertEquals(at(5), syncSessions.lastCompletedAt(connection.id, setOf(SyncType.MANUAL, SyncType.INCREMENTAL)))
        assertEquals(at(12), syncSessions.lastCompletedAt(connection.id, setOf(SyncType.CRON_FALLBACK)))
        assertEquals(null, syncSessions.lastCompletedAt(connection.id, setOf(SyncType.HISTORICAL)))
        assertEquals(null, syncSessions.lastCompletedAt(connection.id, emptySet()))
    }

    @Test
    fun `the queued session lookup finds only the connection's newest not-started session after the cutoff (AOS-630)`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val connection = newConnection(userId)
        val other = newConnection(PostgresTestDatabase.newUser())
        val at = { m: Long -> java.time.Instant.parse("2026-10-18T09:00:00Z").plusSeconds(m * 60) }
        suspend fun session(conn: UserConnection, status: SyncSessionStatus, created: java.time.Instant) =
            syncSessions.save(SyncSession(userId = conn.userId, connectionId = conn.id, syncType = SyncType.INCREMENTAL, status = status,
                createdAt = created, updatedAt = created))
        session(connection, SyncSessionStatus.QUEUED, at(0))          // before the cutoff: treated as lost
        val newest = session(connection, SyncSessionStatus.QUEUED, at(20))
        session(connection, SyncSessionStatus.QUEUED, at(15))
        session(connection, SyncSessionStatus.IN_PROGRESS, at(25))    // started: doesn't coalesce
        session(connection, SyncSessionStatus.COMPLETED, at(26))
        session(other, SyncSessionStatus.QUEUED, at(30))              // another connection

        assertEquals(newest.id, syncSessions.findQueued(connection.id, createdAfter = at(10))?.id)
        assertEquals(null, syncSessions.findQueued(connection.id, createdAfter = at(21)))
    }
}
