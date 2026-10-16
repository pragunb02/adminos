package dev.adminos.api.domain.ingestion.connection

import dev.adminos.api.domain.identity.assertWireFormat
import dev.adminos.api.domain.ingestion.sync.InMemorySyncSessionRepository
import dev.adminos.api.infrastructure.crypto.TokenEncryptor
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/** AOS-312: ConnectionService ownership checks, token encryption and lookups; connection DTOs. */
class ConnectionServiceCoverageTest {

    private val connections = InMemoryConnectionRepository()
    private val syncSessions = InMemorySyncSessionRepository()
    private val encryptor = TokenEncryptor(java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))
    private val service = ConnectionService(connections, syncSessions, encryptor)
    private val userId = UUID.randomUUID()

    @Test
    fun `oauth tokens are stored encrypted, not in plaintext`() = runBlocking<Unit> {
        val conn = service.createConnection(userId, SourceType.GMAIL, "pragun.b@gmail.com", accessToken = "ya29.secret", refreshToken = "1//0g-secret")

        val stored = assertNotNull(connections.findById(conn.id))
        assertNotEquals("ya29.secret", stored.accessToken)
        assertEquals("ya29.secret", encryptor.decrypt(stored.accessToken!!, "user_connections:${stored.id}:access_token"))
        assertEquals("1//0g-secret", encryptor.decrypt(stored.refreshToken!!, "user_connections:${stored.id}:refresh_token"))
    }

    @Test
    fun `without an encryptor tokens are kept as given`() = runBlocking<Unit> {
        val plain = ConnectionService(connections, syncSessions)

        val conn = plain.createConnection(userId, SourceType.GMAIL, "pragun.b@gmail.com", accessToken = "ya29.dev")

        assertEquals("ya29.dev", conn.accessToken)
        assertNull(conn.refreshToken)
    }

    @Test
    fun `reconnecting a disconnected source reactivates the same connection (AOS-613)`() = runBlocking<Unit> {
        val old = service.createConnection(userId, SourceType.GMAIL, "me@gmail.com", "ya29.old", "1//old")
        service.disconnect(userId, old.id)

        val again = service.createConnection(userId, SourceType.GMAIL, null, "ya29.new", "1//new")

        assertEquals(ConnectionStatus.CONNECTED, again.status)
        assertEquals(old.id, again.id)
        assertEquals("me@gmail.com", again.gmailAddress) // kept when the new grant doesn't say
        assertEquals("ya29.new", encryptor.decrypt(again.accessToken!!, "user_connections:${again.id}:access_token"))
        assertEquals(null, again.historyId)
    }

    @Test
    fun `disconnecting an unknown connection is not found`() = runBlocking<Unit> {
        assertFailsWith<ConnectionNotFoundException> { service.disconnect(userId, UUID.randomUUID()) }
    }

    @Test
    fun `syncing an unknown connection is not found`() = runBlocking<Unit> {
        assertFailsWith<ConnectionNotFoundException> { service.triggerManualSync(userId, UUID.randomUUID()) }
    }

    @Test
    fun `syncing another user's connection is not found`() = runBlocking<Unit> {
        val theirs = service.createConnection(UUID.randomUUID(), SourceType.GMAIL, "them@gmail.com")

        assertFailsWith<ConnectionNotFoundException> { service.triggerManualSync(userId, theirs.id) }
        assertTrue(syncSessions.findByUserId(userId).isEmpty())
    }

    @Test
    fun `getConnection returns the user's own connection`() = runBlocking<Unit> {
        val mine = service.createConnection(userId, SourceType.SMS)

        assertEquals(mine.id, service.getConnection(userId, mine.id).id)
    }

    @Test
    fun `getConnection hides another user's connection`() = runBlocking<Unit> {
        val theirs = service.createConnection(UUID.randomUUID(), SourceType.SMS)

        assertFailsWith<ConnectionNotFoundException> { service.getConnection(userId, theirs.id) }
    }

    @Test
    fun `getConnection of an unknown id is not found`() = runBlocking<Unit> {
        assertFailsWith<ConnectionNotFoundException> { service.getConnection(userId, UUID.randomUUID()) }
    }

    @Test
    fun `gmail connections are found by their address`() = runBlocking<Unit> {
        val gmail = service.createConnection(userId, SourceType.GMAIL, "pragun.b@gmail.com")

        assertEquals(gmail.id, service.findByGmailAddress("pragun.b@gmail.com")?.id)
        assertNull(service.findByGmailAddress("someone.else@gmail.com"))
    }

    @Test
    fun `a non-gmail connection is never found by a gmail address`() = runBlocking<Unit> {
        service.createConnection(userId, SourceType.SMS, gmailAddress = "pragun.b@gmail.com")

        assertNull(service.findByGmailAddress("pragun.b@gmail.com"))
    }

    @Test
    fun `only connected connections of a source are listed for syncing`() = runBlocking<Unit> {
        val live = service.createConnection(userId, SourceType.GMAIL, "a@gmail.com")
        val other = UUID.randomUUID()
        service.disconnect(other, service.createConnection(other, SourceType.GMAIL, "b@gmail.com").id)
        service.createConnection(userId, SourceType.SMS)

        assertEquals(listOf(live.id), service.findConnectedBySourceType(SourceType.GMAIL).map { it.id })
    }

    @Test
    fun `getOrCreateConnection returns the existing connection`() = runBlocking<Unit> {
        val existing = service.createConnection(userId, SourceType.SMS)

        assertEquals(existing.id, service.getOrCreateConnection(userId, SourceType.SMS).id)
    }

    @Test
    fun `getOrCreateConnection creates a connected one when there is none`() = runBlocking<Unit> {
        val created = service.getOrCreateConnection(userId, SourceType.PDF)

        assertEquals(ConnectionStatus.CONNECTED, created.status)
        assertEquals(created.id, connections.findByUserAndSource(userId, SourceType.PDF)?.id)
    }

    @Test
    fun `connection exceptions carry readable messages`() {
        assertEquals("Connection not found", ConnectionNotFoundException().message)
        assertEquals("Connection is not in connected state", ConnectionNotConnectedException().message)
    }

    @Test
    fun `connection response exposes status and sync details but never tokens`() {
        val conn = UserConnection(
            userId = userId, sourceType = SourceType.ACCOUNT_AGGREGATOR, status = ConnectionStatus.ERROR,
            accessToken = "ya29.secret", lastSyncedAt = Instant.parse("2026-10-16T06:00:00Z"), lastSyncStatus = "failed", totalSynced = 42
        )

        val res = ConnectionResponse.from(conn)

        assertEquals("account_aggregator", res.sourceType)
        assertEquals("error", res.status)
        assertEquals("2026-10-16T06:00:00Z", res.lastSyncedAt)
        assertEquals(42, res.totalSynced)
        assertNull(ConnectionResponse.from(conn.copy(lastSyncedAt = null)).lastSyncedAt)
    }

    @Test
    fun `create connection request round-trips and needs a source type`() =
        assertWireFormat(CreateConnectionRequest.serializer(), CreateConnectionRequest("sms"),
            CreateConnectionRequest("gmail", "4/0Ab", "http://localhost:3000/settings/connections"), """{"code":"4/0Ab"}""")

    @Test
    fun `connection response round-trips with and without sync details`() {
        val minimal = ConnectionResponse("c-1", "sms", "connected", createdAt = "2026-10-16T06:00:00Z")
        assertWireFormat(ConnectionResponse.serializer(), minimal,
            minimal.copy(gmailAddress = "a@gmail.com", lastSyncedAt = "2026-10-16T07:00:00Z", lastSyncStatus = "completed", totalSynced = 7),
            """{"id":"c-1","sourceType":"sms"}""")
    }
}
