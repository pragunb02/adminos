package dev.adminos.api.domain.ingestion.connection

import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import dev.adminos.api.infrastructure.database.repositories.ExposedConnectionRepository
import dev.adminos.api.infrastructure.database.repositories.ExposedSyncSessionRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** AOS-312: ConnectionService over the real Postgres repositories (uq_conn_source applies). */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConnectionServicePostgresIntegrationTest {

    private val service = ConnectionService(ExposedConnectionRepository(), ExposedSyncSessionRepository())

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    @Test
    fun `disconnecting a gmail connection clears its tokens in the database`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val conn = service.createConnection(userId, SourceType.GMAIL, "pg-${userId}@example.com", accessToken = "ya29.a", refreshToken = "1//0g")

        service.disconnect(userId, conn.id)

        val stored = assertNotNull(service.getConnection(userId, conn.id))
        assertEquals(ConnectionStatus.DISCONNECTED, stored.status)
        assertNull(stored.accessToken)
        assertNull(stored.refreshToken)
    }

    @Test
    fun `a manual sync is persisted for a connected connection`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val conn = service.createConnection(userId, SourceType.SMS)

        val session = service.triggerManualSync(userId, conn.id)

        assertEquals(conn.id, session.connectionId)
    }

    @Test
    fun `reconnecting the same gmail address after a disconnect works`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val address = "again-${userId}@example.com"
        val first = service.createConnection(userId, SourceType.GMAIL, address)
        service.disconnect(userId, first.id)

        val again = service.createConnection(userId, SourceType.GMAIL, address)

        assertEquals(ConnectionStatus.CONNECTED, again.status)
        assertEquals(ConnectionStatus.CONNECTED, service.findByGmailAddress(address)?.status)
    }
}
