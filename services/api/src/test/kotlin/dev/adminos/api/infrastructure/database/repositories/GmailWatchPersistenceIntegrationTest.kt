package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.ingestion.connection.ConnectionStatus
import dev.adminos.api.domain.ingestion.connection.GmailWatch
import dev.adminos.api.domain.ingestion.connection.SourceType
import dev.adminos.api.domain.ingestion.connection.UserConnection
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.UserConnectionsTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** AOS-405: the V26 watch columns, and the narrow writes that never clobber what the worker wrote. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GmailWatchPersistenceIntegrationTest {

    private val connections = ExposedConnectionRepository()
    private val t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    private val watch = GmailWatch(expiresAt = t0.plus(7, ChronoUnit.DAYS), historyId = "4455667", error = null, checkedAt = t0)

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private suspend fun gmail(watch: GmailWatch = GmailWatch.NONE) = connections.save(UserConnection(
        userId = PostgresTestDatabase.newUser(), sourceType = SourceType.GMAIL, status = ConnectionStatus.CONNECTED,
        gmailAddress = "w-${UUID.randomUUID()}@gmail.com", historyId = "1000", accessToken = "v2:sealed-a", tokenExpiresAt = t0, watch = watch
    ))

    /** What the Go worker does after a sync: moves the history ID and records success (D-59). */
    private suspend fun workerSynced(id: UUID, historyId: String) = dbQuery {
        UserConnectionsTable.update({ UserConnectionsTable.id eq id }) {
            it[UserConnectionsTable.historyId] = historyId
            it[lastSyncStatus] = "success"
        }
    }

    @Test
    fun `the watch round-trips through save, update and read`() = runBlocking<Unit> {
        val conn = gmail(watch)

        assertEquals(watch, connections.findById(conn.id)?.watch)

        val failed = watch.copy(error = "Gmail push: Gmail answered HTTP 403", checkedAt = t0.plusSeconds(60))
        connections.update(conn.copy(watch = failed))
        assertEquals(failed, connections.findById(conn.id)?.watch)

        connections.update(conn.copy(watch = GmailWatch.NONE))
        assertEquals(GmailWatch.NONE, connections.findById(conn.id)?.watch)
    }

    @Test
    fun `storing a watch never rewinds the history id the worker moved meanwhile`() = runBlocking<Unit> {
        val conn = gmail()
        workerSynced(conn.id, "2000")

        connections.updateWatch(conn.id, watch)

        val stored = assertNotNull(connections.findById(conn.id))
        assertEquals(watch, stored.watch)
        assertEquals("2000", stored.historyId)
        assertEquals("success", stored.lastSyncStatus)
    }

    @Test
    fun `storing a refreshed access token changes only the token and its expiry`() = runBlocking<Unit> {
        val conn = gmail(watch)
        workerSynced(conn.id, "3000")

        connections.updateAccessToken(conn.id, "v2:sealed-b", t0.plusSeconds(3599))

        val stored = assertNotNull(connections.findById(conn.id))
        assertEquals("v2:sealed-b", stored.accessToken)
        assertEquals(t0.plusSeconds(3599), stored.tokenExpiresAt)
        assertEquals("3000", stored.historyId)
        assertEquals(watch, stored.watch)
    }

    @Test
    fun `marking a connection in error keeps everything else`() = runBlocking<Unit> {
        val conn = gmail(watch)

        connections.markError(conn.id, "gmail access revoked: reconnect Gmail")

        val stored = assertNotNull(connections.findById(conn.id))
        assertEquals(ConnectionStatus.ERROR, stored.status)
        assertEquals("gmail access revoked: reconnect Gmail", stored.lastError)
        assertEquals("1000", stored.historyId)
        assertEquals(watch, stored.watch)
    }
}
