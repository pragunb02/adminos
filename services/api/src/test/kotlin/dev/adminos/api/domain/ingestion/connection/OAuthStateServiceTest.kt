package dev.adminos.api.domain.ingestion.connection

import dev.adminos.api.domain.scheduling.TestClock
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/** AOS-406: the OAuth state lifecycle (issue, single use, expiry, binding) and PKCE. */
class OAuthStateServiceTest {

    private val now = Instant.parse("2026-10-17T06:00:00Z")
    private val clock = TestClock(now)
    private val repo = InMemoryOAuthStateRepository()
    private val encryptor = randomTokenEncryptor()
    private val service = OAuthStateService(repo, encryptor, clock)
    private val me = OAuthStateClaim(UUID.randomUUID(), OAuthPurpose.GMAIL_CONNECT, "http://localhost:3000/connections/gmail/callback")

    @Test
    fun `an issued state is consumed once and returns the verifier behind its challenge`() = runBlocking<Unit> {
        val issued = service.issue(me)

        val verifier = service.consume(issued.state, me)

        assertEquals(issued.codeChallenge, OAuthStateService.codeChallenge(verifier))
        assertFailsWith<OAuthStateException.Reused> { service.consume(issued.state, me) }
    }

    @Test
    fun `only the hash of the state and an encrypted verifier are stored`() = runBlocking<Unit> {
        val issued = service.issue(me)

        val stored = assertNotNull(repo.findByHash(OAuthStateService.sha256Hex(issued.state)))
        assertNull(repo.findByHash(issued.state))
        assertTrue(stored.codeVerifier.startsWith("v2:"))
        assertEquals(now.plus(Duration.ofMinutes(10)), stored.expiresAt)
        assertEquals(me.userId, stored.userId)
        assertFalse(issued.state in issued.toString() || issued.state in stored.toString())
    }

    @Test
    fun `states and verifiers are random and url-safe`() = runBlocking<Unit> {
        val a = service.issue(me)
        val b = service.issue(me)

        assertNotEquals(a.state, b.state)
        assertNotEquals(a.codeChallenge, b.codeChallenge)
        assertTrue(a.state.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertTrue(a.codeChallenge.matches(Regex("[A-Za-z0-9_-]{43}")))
    }

    @Test
    fun `a state is refused once ten minutes have passed`() = runBlocking<Unit> {
        val issued = service.issue(me)
        clock.advance(Duration.ofMinutes(9).plusSeconds(59))
        val stillFresh = service.issue(me)
        clock.advance(Duration.ofSeconds(1))

        assertFailsWith<OAuthStateException.Expired> { service.consume(issued.state, me) }
        service.consume(stillFresh.state, me)
    }

    @Test
    fun `a missing, blank, overlong or unknown state is a mismatch`() = runBlocking<Unit> {
        service.issue(me)

        for (bad in listOf(null, "", "  ", "x".repeat(129), "never-issued")) {
            assertFailsWith<OAuthStateException.Mismatch>("state=$bad") { service.consume(bad, me) }
        }
    }

    @Test
    fun `a state is bound to its user, purpose and redirect uri, and a mismatch does not use it up`() = runBlocking<Unit> {
        val issued = service.issue(me)

        assertFailsWith<OAuthStateException.Mismatch> { service.consume(issued.state, me.copy(userId = UUID.randomUUID())) }
        assertFailsWith<OAuthStateException.Mismatch> { service.consume(issued.state, me.copy(redirectUri = "https://evil.example/cb")) }
        service.consume(issued.state, me)
    }

    @Test
    fun `a verifier sealed with another key is a mismatch`() = runBlocking<Unit> {
        val issued = service.issue(me)
        val otherKey = OAuthStateService(repo, randomTokenEncryptor(), clock)

        assertFailsWith<OAuthStateException.Mismatch> { otherKey.consume(issued.state, me) }
    }

    @Test
    fun `without an encryptor (dev mode) the verifier is kept as is`() = runBlocking<Unit> {
        val plain = OAuthStateService(repo, encryptor = null, clock = clock)
        val issued = plain.issue(me)

        assertEquals(issued.codeChallenge, OAuthStateService.codeChallenge(plain.consume(issued.state, me)))
    }

    @Test
    fun `a race between two redemptions lets only one through`() = runBlocking<Unit> {
        val issued = service.issue(me)
        val stored = assertNotNull(repo.findByHash(OAuthStateService.sha256Hex(issued.state)))
        repo.markUsed(stored.id, now) // the other request won between our read and our write
        val racing = object : OAuthStateRepository by repo {
            override suspend fun findByHash(stateHash: String) = stored // still the unused copy
        }

        assertFailsWith<OAuthStateException.Reused> { OAuthStateService(racing, encryptor, clock).consume(issued.state, me) }
    }

    @Test
    fun `issuing clears states that expired over a day ago`() = runBlocking<Unit> {
        val old = service.issue(me)
        clock.advance(Duration.ofDays(1).plusMinutes(10))
        val recent = service.issue(me)
        clock.advance(Duration.ofSeconds(1))

        service.issue(me)

        assertNull(repo.findByHash(OAuthStateService.sha256Hex(old.state)))
        assertNotNull(repo.findByHash(OAuthStateService.sha256Hex(recent.state)))
    }

    @Test
    fun `the pkce challenge is base64url of the verifier's SHA-256 without padding`() {
        val verifier = runtimeToken("v-")
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        val expected = java.util.Base64.getUrlEncoder().encodeToString(digest).trimEnd('=')

        assertEquals(expected, OAuthStateService.codeChallenge(verifier))
        assertEquals(64, OAuthStateService.sha256Hex(verifier).length)
    }

    @Test
    fun `a state row validates itself`() {
        val hash = OAuthStateService.sha256Hex("s")
        assertFailsWith<IllegalArgumentException> { OAuthState(userId = me.userId, purpose = me.purpose, stateHash = "plain", redirectUri = "r", codeVerifier = "v", createdAt = now, expiresAt = now.plusSeconds(1)) }
        assertFailsWith<IllegalArgumentException> { OAuthState(userId = me.userId, purpose = me.purpose, stateHash = hash, redirectUri = " ", codeVerifier = "v", createdAt = now, expiresAt = now.plusSeconds(1)) }
        assertFailsWith<IllegalArgumentException> { OAuthState(userId = me.userId, purpose = me.purpose, stateHash = hash, redirectUri = "r", codeVerifier = "v", createdAt = now, expiresAt = now) }
        assertFailsWith<IllegalArgumentException> { OAuthStateService(repo, encryptor, clock, ttl = Duration.ofHours(2)) }
        assertFailsWith<IllegalArgumentException> { OAuthStateService(repo, encryptor, clock, ttl = Duration.ZERO) }
    }
}
