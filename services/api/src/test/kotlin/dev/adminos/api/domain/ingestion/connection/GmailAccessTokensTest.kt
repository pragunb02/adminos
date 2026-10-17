package dev.adminos.api.domain.ingestion.connection

import dev.adminos.api.domain.scheduling.TestClock
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/** AOS-405: the API's own Gmail access tokens: decrypt, refresh early, store encrypted, revocation. */
class GmailAccessTokensTest {

    private val now = Instant.parse("2026-10-17T06:00:00Z")
    private val clock = TestClock(now)
    private val connections = InMemoryConnectionRepository()
    private val encryptor = randomTokenEncryptor()
    private val refresher = FakeTokenRefresher { clock.instant().plusSeconds(3599) }
    private val tokens = GmailAccessTokens(connections, refresher, encryptor, clock)

    private suspend fun connection(access: String?, refresh: String?, expiresAt: Instant?, key: Boolean = true): UserConnection {
        val id = UUID.randomUUID()
        fun seal(v: String?, field: String) = v?.let { if (key) encryptor.encrypt(it, "user_connections:$id:$field") else it }
        return connections.save(UserConnection(
            id = id, userId = UUID.randomUUID(), sourceType = SourceType.GMAIL, status = ConnectionStatus.CONNECTED,
            accessToken = seal(access, "access_token"), refreshToken = seal(refresh, "refresh_token"), tokenExpiresAt = expiresAt
        ))
    }

    @Test
    fun `a token valid for more than two minutes is decrypted and used as is`() = runBlocking<Unit> {
        val access = runtimeToken()
        val conn = connection(access, runtimeToken("1//"), now.plusSeconds(121))

        assertEquals(access, tokens.current(conn))
        assertTrue(refresher.refreshTokens.isEmpty())
    }

    @Test
    fun `a token expiring within two minutes is refreshed and the new one stored encrypted with its expiry`() = runBlocking<Unit> {
        val refresh = runtimeToken("1//")
        val conn = connection(runtimeToken(), refresh, now.plusSeconds(120))

        val fresh = tokens.current(conn)

        assertEquals(listOf(refresh), refresher.refreshTokens.toList())
        val stored = assertNotNull(connections.findById(conn.id))
        assertNotEquals(fresh, stored.accessToken, "never stored in plain text")
        assertEquals(fresh, encryptor.decrypt(assertNotNull(stored.accessToken), "user_connections:${conn.id}:access_token"))
        assertEquals(now.plusSeconds(3599), stored.tokenExpiresAt)
    }

    @Test
    fun `a token with no known expiry or no access token is refreshed`() = runBlocking<Unit> {
        tokens.current(connection(runtimeToken(), runtimeToken("1//"), expiresAt = null))
        tokens.current(connection(access = null, refresh = runtimeToken("1//"), expiresAt = now.plusSeconds(3000)))

        assertEquals(2, refresher.refreshTokens.size)
    }

    @Test
    fun `no refresh token means the tokens can't be used`() = runBlocking<Unit> {
        val conn = connection(runtimeToken(), refresh = null, expiresAt = now.minusSeconds(1))

        assertFailsWith<GmailAccessException.Unreadable> { tokens.current(conn) }
    }

    @Test
    fun `a token sealed with another key or for another row can't be used`() = runBlocking<Unit> {
        val conn = connection(runtimeToken(), runtimeToken("1//"), now.plusSeconds(3000))
        val otherKey = GmailAccessTokens(connections, refresher, randomTokenEncryptor(), clock)
        val moved = conn.copy(id = UUID.randomUUID())

        assertFailsWith<GmailAccessException.Unreadable> { otherKey.current(conn) }
        assertFailsWith<GmailAccessException.Unreadable> { tokens.current(moved) }
    }

    @Test
    fun `a plain-text legacy token is refused rather than sent to google`() = runBlocking<Unit> {
        val conn = connection(runtimeToken(), runtimeToken("1//"), now.plusSeconds(3000), key = false)

        assertFailsWith<GmailAccessException.Unreadable> { tokens.current(conn) }
    }

    @Test
    fun `without an encryptor (dev mode) tokens are used and stored as given`() = runBlocking<Unit> {
        val plain = GmailAccessTokens(connections, refresher, encryptor = null, clock = clock)
        val access = runtimeToken()
        val conn = connection(access, runtimeToken("1//"), now.plusSeconds(3000), key = false)
        val expiring = connection(runtimeToken(), runtimeToken("1//"), now, key = false)

        assertEquals(access, plain.current(conn))
        val fresh = plain.current(expiring)
        assertEquals(fresh, connections.findById(expiring.id)?.accessToken)
    }

    @Test
    fun `a refused refresh token marks the connection error with the worker's reason`() = runBlocking<Unit> {
        val conn = connection(runtimeToken(), runtimeToken("1//"), now.minusSeconds(1))
        refresher.failure = GmailAccessException.Revoked()

        assertFailsWith<GmailAccessException.Revoked> { tokens.current(conn) }

        val stored = assertNotNull(connections.findById(conn.id))
        assertEquals(ConnectionStatus.ERROR, stored.status)
        assertEquals("gmail access revoked: reconnect Gmail", stored.lastError)
    }

    @Test
    fun `an unreachable token service leaves the connection as it was`() = runBlocking<Unit> {
        val conn = connection(runtimeToken(), runtimeToken("1//"), now.minusSeconds(1))
        refresher.failure = GmailAccessException.Unavailable()

        assertFailsWith<GmailAccessException.Unavailable> { tokens.current(conn) }

        assertEquals(ConnectionStatus.CONNECTED, connections.findById(conn.id)?.status)
    }
}
