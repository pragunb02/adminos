package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.ingestion.connection.OAuthPurpose
import dev.adminos.api.domain.ingestion.connection.OAuthStateClaim
import dev.adminos.api.domain.ingestion.connection.OAuthStateException
import dev.adminos.api.domain.ingestion.connection.OAuthStateService
import dev.adminos.api.domain.ingestion.connection.randomTokenEncryptor
import dev.adminos.api.domain.scheduling.TestClock
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** AOS-406: OAuth states on real Postgres (V27): round-trip, single use under concurrency, clean-up. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OAuthStateRepositoryIntegrationTest {

    private val repo = ExposedOAuthStateRepository()
    private val clock = TestClock(Instant.now().truncatedTo(ChronoUnit.MILLIS))
    private val service = OAuthStateService(repo, randomTokenEncryptor(), clock)

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    private suspend fun claim() = OAuthStateClaim(PostgresTestDatabase.newUser(), OAuthPurpose.GMAIL_CONNECT, "http://localhost:3000/connections/gmail/callback")

    @Test
    fun `a state round-trips and is consumed once`() = runBlocking<Unit> {
        val me = claim()
        val issued = service.issue(me)

        val stored = assertNotNull(repo.findByHash(OAuthStateService.sha256Hex(issued.state)))
        assertEquals(me.userId, stored.userId)
        assertEquals(OAuthPurpose.GMAIL_CONNECT, stored.purpose)
        assertEquals(clock.instant().plus(Duration.ofMinutes(10)), stored.expiresAt)
        assertNull(stored.usedAt)

        assertEquals(issued.codeChallenge, OAuthStateService.codeChallenge(service.consume(issued.state, me)))
        assertNotNull(repo.findByHash(stored.stateHash)?.usedAt)
        assertFailsWith<OAuthStateException.Reused> { service.consume(issued.state, me) }
    }

    @Test
    fun `a sign-in state has no user and round-trips once (V32, AOS-618)`() = runBlocking<Unit> {
        val login = OAuthStateClaim.login("http://localhost:3000/auth/callback")
        val issued = service.issue(login)
        val stored = assertNotNull(repo.findByHash(OAuthStateService.sha256Hex(issued.state)))
        assertNull(stored.userId)
        assertEquals(OAuthPurpose.LOGIN, stored.purpose)
        assertEquals(issued.codeChallenge, OAuthStateService.codeChallenge(service.consume(issued.state, login)))
        assertFailsWith<OAuthStateException.Reused> { service.consume(issued.state, login) }
        // Not redeemable as a Gmail consent by anyone.
        val other = service.issue(login)
        assertFailsWith<OAuthStateException.Mismatch> { service.consume(other.state, claim()) }
    }

    @Test
    fun `the database refuses a userless state for any purpose but sign-in`() = runBlocking<Unit> {
        val e = runCatching {
            dev.adminos.api.infrastructure.database.dbQuery {
                org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.current().exec(
                    "INSERT INTO oauth_states (id, user_id, purpose, state_hash, redirect_uri, code_verifier, created_at, expires_at) " +
                        "VALUES ('${java.util.UUID.randomUUID()}', NULL, 'gmail_connect', '${"a".repeat(64)}', 'x', 'y', now(), now() + interval '1 minute')"
                )
            }
        }.exceptionOrNull()
        assertNotNull(e, "the check constraint must reject it")
    }

    @Test
    fun `only one of many concurrent redemptions marks a state used`() = runBlocking<Unit> {
        val issued = service.issue(claim())
        val id = assertNotNull(repo.findByHash(OAuthStateService.sha256Hex(issued.state))).id

        val results = (1..8).map { async { repo.markUsed(id, clock.instant()) } }.awaitAll()

        assertEquals(1, results.count { it })
    }

    @Test
    fun `states that expired before a time are deleted, newer ones kept`() = runBlocking<Unit> {
        val old = service.issue(claim())
        clock.advance(Duration.ofHours(1))
        val fresh = service.issue(claim())

        val deleted = repo.deleteExpiredBefore(clock.instant())

        assertEquals(true, deleted >= 1)
        assertNull(repo.findByHash(OAuthStateService.sha256Hex(old.state)))
        assertNotNull(repo.findByHash(OAuthStateService.sha256Hex(fresh.state)))
    }
}
