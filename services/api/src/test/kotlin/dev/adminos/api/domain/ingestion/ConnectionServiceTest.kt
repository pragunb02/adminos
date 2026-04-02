package dev.adminos.api.domain.ingestion

import dev.adminos.api.domain.ingestion.connection.*
import dev.adminos.api.domain.ingestion.sync.InMemorySyncSessionRepository
import dev.adminos.api.domain.ingestion.sync.SyncSessionStatus
import dev.adminos.api.domain.ingestion.sync.SyncType
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.*
class ConnectionServiceTest {

    private lateinit var connRepo: InMemoryConnectionRepository
    private lateinit var syncRepo: InMemorySyncSessionRepository
    private lateinit var service: ConnectionService

    @BeforeTest
    fun setup() {
        connRepo = InMemoryConnectionRepository()
        syncRepo = InMemorySyncSessionRepository()
        service = ConnectionService(connRepo, syncRepo)
    }

    @Test
    fun `createConnection creates a new Gmail connection`() = runBlocking {
        val userId = UUID.randomUUID()
        val conn = service.createConnection(userId, SourceType.GMAIL, gmailAddress = "test@gmail.com")

        assertEquals(ConnectionStatus.CONNECTED, conn.status)
        assertEquals(SourceType.GMAIL, conn.sourceType)
        assertEquals("test@gmail.com", conn.gmailAddress)
        assertEquals(userId, conn.userId)
    }

    @Test
    fun `createConnection returns existing if already connected`() = runBlocking {
        val userId = UUID.randomUUID()
        val first = service.createConnection(userId, SourceType.GMAIL, gmailAddress = "test@gmail.com")
        val second = service.createConnection(userId, SourceType.GMAIL, gmailAddress = "test@gmail.com")

        assertEquals(first.id, second.id)
    }

    @Test
    fun `disconnect updates status and clears tokens`() = runBlocking {
        val userId = UUID.randomUUID()
        val conn = service.createConnection(userId, SourceType.GMAIL, accessToken = "tok", refreshToken = "ref")

        val disconnected = service.disconnect(userId, conn.id)

        assertEquals(ConnectionStatus.DISCONNECTED, disconnected.status)
        assertNull(disconnected.accessToken)
        assertNull(disconnected.refreshToken)
    }

    @Test
    fun `disconnect throws for wrong user`() = runBlocking {
        val userId = UUID.randomUUID()
        val otherUser = UUID.randomUUID()
        val conn = service.createConnection(userId, SourceType.GMAIL)

        assertFailsWith<ConnectionNotFoundException> {
            service.disconnect(otherUser, conn.id)
        }
    }

    @Test
    fun `triggerManualSync creates queued sync session`() = runBlocking {
        val userId = UUID.randomUUID()
        val conn = service.createConnection(userId, SourceType.GMAIL)

        val session = service.triggerManualSync(userId, conn.id)

        assertEquals(SyncSessionStatus.QUEUED, session.status)
        assertEquals(SyncType.MANUAL, session.syncType)
        assertEquals(conn.id, session.connectionId)
    }

    @Test
    fun `triggerManualSync throws for disconnected connection`() = runBlocking {
        val userId = UUID.randomUUID()
        val conn = service.createConnection(userId, SourceType.GMAIL)
        service.disconnect(userId, conn.id)

        assertFailsWith<ConnectionNotConnectedException> {
            service.triggerManualSync(userId, conn.id)
        }
    }

    @Test
    fun `getUserConnections returns only user connections`() = runBlocking {
        val user1 = UUID.randomUUID()
        val user2 = UUID.randomUUID()

        service.createConnection(user1, SourceType.GMAIL)
        service.createConnection(user1, SourceType.SMS)
        service.createConnection(user2, SourceType.GMAIL)

        val user1Conns = service.getUserConnections(user1)
        val user2Conns = service.getUserConnections(user2)

        assertEquals(2, user1Conns.size)
        assertEquals(1, user2Conns.size)
    }
}
