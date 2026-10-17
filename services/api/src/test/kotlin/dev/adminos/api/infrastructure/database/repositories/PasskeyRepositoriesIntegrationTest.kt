package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.Passkey
import dev.adminos.api.domain.identity.PasskeyService
import dev.adminos.api.domain.identity.Session
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

/** AOS-617 on real Postgres (V62): passkeys, recovery codes (single use under concurrency), the session's second factor. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PasskeyRepositoriesIntegrationTest {

    private val passkeys = ExposedPasskeyRepository()
    private val codes = ExposedRecoveryCodeRepository()
    private val sessions = ExposedSessionRepository()
    private val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    @Test
    fun `a passkey round-trips and records its use`() = runBlocking<Unit> {
        val me = PostgresTestDatabase.newUser()
        val p = passkeys.save(Passkey(userId = me, credentialId = "cred-${UUID.randomUUID()}", publicKeyCose = "pQECAyYgASFYIA", signatureCount = 0,
            name = "MacBook", transports = setOf("internal", "hybrid"), createdAt = now))
        assertEquals(listOf(p), passkeys.findByUser(me))
        passkeys.recordUse(p.id, 7, now.plusSeconds(5))
        val used = passkeys.findByUser(me).single()
        assertEquals(7, used.signatureCount)
        assertEquals(now.plusSeconds(5), used.lastUsedAt)
        assertTrue(passkeys.findByUser(PostgresTestDatabase.newUser()).isEmpty())
    }

    @Test
    fun `the same credential can't be registered twice`() = runBlocking<Unit> {
        val me = PostgresTestDatabase.newUser()
        val id = "cred-${UUID.randomUUID()}"
        passkeys.save(Passkey(userId = me, credentialId = id, publicKeyCose = "k", signatureCount = 0, name = "a", createdAt = now))
        val second = runCatching { passkeys.save(Passkey(userId = me, credentialId = id, publicKeyCose = "k", signatureCount = 0, name = "b", createdAt = now)) }
        assertTrue(second.isFailure)
    }

    @Test
    fun `a recovery code works once, even when used concurrently, and replacing them voids the old ones`() = runBlocking<Unit> {
        val me = PostgresTestDatabase.newUser()
        val hashes = (1..10).map { PasskeyService.sha256("CODE$it") }
        codes.replaceAll(me, hashes, now)
        assertEquals(10, codes.countUnused(me))
        val wins = (1..8).map { async { codes.consume(me, hashes[0], now) } }.awaitAll()
        assertEquals(1, wins.count { it })
        assertEquals(9, codes.countUnused(me))
        assertEquals(false, codes.consume(PostgresTestDatabase.newUser(), hashes[1], now), "another user's code")
        codes.replaceAll(me, listOf(PasskeyService.sha256("NEW")), now)
        assertEquals(false, codes.consume(me, hashes[1], now))
        assertEquals(1, codes.countUnused(me))
    }

    @Test
    fun `a session starts pending and records when it passed the second factor`() = runBlocking<Unit> {
        val me = PostgresTestDatabase.newUser()
        val s = sessions.save(Session(userId = me, tokenHash = JwtService.hashToken(UUID.randomUUID().toString()), expiresAt = now.plusSeconds(3600)))
        assertNull(sessions.findById(s.id)?.secondFactorAt)
        sessions.markSecondFactor(s.id, now)
        assertEquals(now, assertNotNull(sessions.findById(s.id)).secondFactorAt)
    }
}
