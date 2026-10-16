package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.identity.Session
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdentityRepositoriesIntegrationTest {

    private val sessions = ExposedSessionRepository()
    private val deviceTokens = ExposedDeviceTokenRepository()

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    @Test
    fun `session round-trips including ip address`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val session = Session(
            userId = userId,
            tokenHash = "hash-${UUID.randomUUID()}",
            ipAddress = "203.0.113.7",
            userAgent = "test-agent",
            expiresAt = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS)
        )
        sessions.save(session)

        val found = assertNotNull(sessions.findByTokenHash(session.tokenHash))
        assertEquals(session.id, found.id)
        assertEquals(userId, found.userId)
        assertEquals("203.0.113.7", found.ipAddress)
        assertEquals("test-agent", found.userAgent)
        assertEquals(session.expiresAt, found.expiresAt.truncatedTo(ChronoUnit.MILLIS))
        assertNull(found.revokedAt)
    }

    @Test
    fun `revoked session is no longer active`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val session = Session(userId = userId, tokenHash = "hash-${UUID.randomUUID()}", expiresAt = Instant.now().plus(1, ChronoUnit.DAYS))
        sessions.save(session)
        assertEquals(listOf(session.id), sessions.findActiveByUserId(userId).map { it.id })

        sessions.updateLastActive(session.id)
        sessions.revoke(session.id)

        assertTrue(sessions.findActiveByUserId(userId).isEmpty())
        assertNotNull(sessions.findByTokenHash(session.tokenHash)?.revokedAt)
    }

    @Test
    fun `device token round-trips, is touched and revoked`() = runBlocking<Unit> {
        val userId = PostgresTestDatabase.newUser()
        val t = dev.adminos.api.domain.identity.DeviceToken(
            userId = userId, name = "My Mac", scopes = setOf(dev.adminos.api.domain.identity.Scopes.INGEST_SMS),
            createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS), expiresAt = Instant.now().plus(365, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS)
        )
        deviceTokens.save(t)
        assertEquals(t, deviceTokens.findById(t.id)?.let { it.copy(createdAt = it.createdAt.truncatedTo(ChronoUnit.MILLIS), expiresAt = it.expiresAt.truncatedTo(ChronoUnit.MILLIS)) })
        deviceTokens.touch(t.id, Instant.now())
        deviceTokens.revoke(t.id, Instant.now())
        val after = deviceTokens.findByUser(userId).single()
        assertNotNull(after.lastUsedAt); assertNotNull(after.revokedAt)
    }
}
