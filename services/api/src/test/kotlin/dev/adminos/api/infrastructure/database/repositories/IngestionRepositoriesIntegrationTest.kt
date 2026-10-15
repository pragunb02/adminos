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
}
