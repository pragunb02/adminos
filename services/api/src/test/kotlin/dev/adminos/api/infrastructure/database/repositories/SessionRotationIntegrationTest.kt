package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.Session
import dev.adminos.api.domain.notifications.Notification
import dev.adminos.api.domain.notifications.NotificationChannel
import dev.adminos.api.domain.notifications.NotificationType
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

/** AOS-620 on real Postgres (V33): refresh-token rotation, its history, revoke-all, and the `security` notification type. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionRotationIntegrationTest {

    private val sessions = ExposedSessionRepository()

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private fun hash() = JwtService.hashToken(UUID.randomUUID().toString())

    private suspend fun newSession(userId: UUID, at: Instant = Instant.now()) = sessions.save(Session(
        userId = userId, tokenHash = hash(), userAgent = "Mozilla/5.0 (Macintosh) Chrome/140",
        expiresAt = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS), createdAt = at.truncatedTo(ChronoUnit.MILLIS)
    ))

    @Test
    fun `rotation swaps the token once and remembers the old one`() = runBlocking<Unit> {
        val s = newSession(PostgresTestDatabase.newUser())
        val next = hash()
        val at = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        assertTrue(sessions.rotate(s.id, s.tokenHash, next, at))
        assertEquals(s.id, sessions.findByTokenHash(next)?.id)
        assertNull(sessions.findByTokenHash(s.tokenHash))
        val old = assertNotNull(sessions.findRotated(s.tokenHash))
        assertEquals(s.id, old.sessionId)
        assertEquals(at, old.rotatedAt)
        // The old token can't rotate again.
        assertEquals(false, sessions.rotate(s.id, s.tokenHash, hash(), at))
    }

    @Test
    fun `only one of many concurrent rotations of the same token wins`() = runBlocking<Unit> {
        val s = newSession(PostgresTestDatabase.newUser())
        val wins = (1..8).map { async { sessions.rotate(s.id, s.tokenHash, hash(), Instant.now()) } }.awaitAll()
        assertEquals(1, wins.count { it })
    }

    @Test
    fun `revoke-all ends only that user's active sessions`() = runBlocking<Unit> {
        val me = PostgresTestDatabase.newUser()
        val other = PostgresTestDatabase.newUser()
        val a = newSession(me); val b = newSession(me); val theirs = newSession(other)
        assertEquals(2, sessions.revokeAllForUser(me))
        assertTrue(sessions.findActiveByUserId(me).isEmpty())
        assertNotNull(sessions.findById(a.id)?.revokedAt)
        assertNotNull(sessions.findById(b.id)?.revokedAt)
        assertEquals(listOf(theirs.id), sessions.findActiveByUserId(other).map { it.id })
        assertEquals(0, sessions.revokeAllForUser(me))
    }

    @Test
    fun `recent sessions come newest first, revoked ones included`() = runBlocking<Unit> {
        val me = PostgresTestDatabase.newUser()
        val old = newSession(me, Instant.now().minusSeconds(3600))
        val new = newSession(me)
        sessions.revoke(old.id)
        assertEquals(listOf(new.id, old.id), sessions.findRecentByUserId(me, 10).map { it.id })
        assertEquals(listOf(new.id), sessions.findRecentByUserId(me, 1).map { it.id })
    }

    @Test
    fun `a security notification can be stored`() = runBlocking<Unit> {
        val me = PostgresTestDatabase.newUser()
        val saved = ExposedNotificationRepository().save(Notification(userId = me, type = NotificationType.SECURITY, title = "New sign-in to AdminOS",
            body = "Signed in on Chrome on Mac", channel = NotificationChannel.IN_APP))
        assertEquals(NotificationType.SECURITY, ExposedNotificationRepository().findById(saved.id)?.type)
    }
}
