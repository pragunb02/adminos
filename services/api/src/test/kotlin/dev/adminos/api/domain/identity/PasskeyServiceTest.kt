package dev.adminos.api.domain.identity

import dev.adminos.api.domain.scheduling.TestClock
import dev.adminos.api.infrastructure.webauthn.YubicoWebAuthn
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/**
 * Passkeys as the second factor (AOS-617), verified end to end by the real WebAuthn library against a software
 * authenticator: enrolment and its bootstrap, verification, every forgery we could think of, recovery codes, limits.
 */
class PasskeyServiceTest {

    private val appUrl = "http://localhost:3000"
    private val clock = TestClock(Instant.now().truncatedTo(ChronoUnit.SECONDS))
    private val users = InMemoryUserRepository()
    private val sessions = InMemorySessionRepository()
    private val keys = InMemoryPasskeyRepository()
    private val codes = InMemoryRecoveryCodeRepository()
    private val sent = CopyOnWriteArrayList<SecurityAlert>()
    private val webAuthn = YubicoWebAuthn(appUrl)
    private val service = PasskeyService(keys, codes, sessions, users, webAuthn, SignInAlerts({ _, a -> sent += a }), clock)
    private val authenticator = SoftAuthenticator("http://localhost:3000", "localhost")

    private val owner = runBlocking { users.save(User(email = "pragun.b@gmail.com", googleId = "g-owner", name = "Pragun")) }
    private val handle = ByteBuffer.allocate(16).putLong(owner.id.mostSignificantBits).putLong(owner.id.leastSignificantBits).array()

    private fun pendingSession(): UUID = runBlocking {
        sessions.save(Session(userId = owner.id, tokenHash = UUID.randomUUID().toString(), expiresAt = Instant.now().plus(1, ChronoUnit.DAYS))).id
    }

    private suspend fun enrol(session: UUID = pendingSession(), with: SoftAuthenticator = authenticator): Enrolled {
        val options = service.registrationOptions(owner.id, session)
        return service.register(owner.id, session, with.create(options), "MacBook")
    }

    private fun verified(session: UUID) = runBlocking { sessions.findById(session)?.secondFactorAt != null }

    // ── Enrolment and bootstrap ──

    @Test
    fun `the first passkey can be enrolled from a pending session, which it verifies, with ten recovery codes`() = runBlocking<Unit> {
        val session = pendingSession()
        assertEquals(SecondFactorStep.ENROL, service.status(owner.id, session).step)
        val enrolled = enrol(session)
        assertTrue(verified(session))
        assertEquals(10, enrolled.recoveryCodes.size)
        assertEquals(10, enrolled.recoveryCodes.toSet().size)
        assertTrue(enrolled.recoveryCodes.all { Regex("[0-9A-Z]{4}(-[0-9A-Z]{4}){3}").matches(it) })
        assertEquals(10, codes.countUnused(owner.id))
        assertIs<SecurityAlert.PasskeyAdded>(sent.single())
        assertEquals(SecondFactorStep.DONE, service.status(owner.id, session).step)
        assertEquals("MacBook", keys.findByUser(owner.id).single().name)
    }

    @Test
    fun `once a passkey exists a pending session can't add another (an attacker with only Google can't enrol theirs)`() = runBlocking<Unit> {
        enrol()
        val attacker = pendingSession()
        assertEquals(SecondFactorStep.VERIFY, service.status(owner.id, attacker).step)
        assertFailsWith<PasskeyException.NotAllowed> { service.registrationOptions(owner.id, attacker) }
        assertFailsWith<PasskeyException.NotAllowed> { service.register(owner.id, attacker, "{}", "Evil") }
    }

    @Test
    fun `a verified session may add a second passkey, and no new codes are issued`() = runBlocking<Unit> {
        val session = pendingSession()
        enrol(session)
        val second = enrol(session, SoftAuthenticator("http://localhost:3000", "localhost"))
        assertTrue(second.recoveryCodes.isEmpty())
        assertEquals(2, keys.findByUser(owner.id).size)
    }

    @Test
    fun `enrolment refuses forgeries`() = runBlocking<Unit> {
        val forgeries = listOf(
            SoftAuthenticator.Tweaks(origin = "https://evil.example"),
            SoftAuthenticator.Tweaks(origin = "http://localhost:3001"),
            SoftAuthenticator.Tweaks(rpId = "evil.example"),
            SoftAuthenticator.Tweaks(userVerified = false),
            SoftAuthenticator.Tweaks(challenge = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
            SoftAuthenticator.Tweaks(type = "webauthn.get"),
        )
        for (t in forgeries) {
            val session = pendingSession()
            val options = service.registrationOptions(owner.id, session)
            assertFailsWith<PasskeyException.Rejected>(t.toString()) { service.register(owner.id, session, authenticator.create(options, t), "x") }
            assertFalse(verified(session), "$t must not verify the session")
        }
        assertTrue(keys.findByUser(owner.id).isEmpty())
    }

    @Test
    fun `garbage instead of a WebAuthn response is rejected, not a crash`() = runBlocking<Unit> {
        val session = pendingSession()
        service.registrationOptions(owner.id, session)
        assertFailsWith<PasskeyException.Rejected> { service.register(owner.id, session, """{"id":"x"}""", "x") }
    }

    // ── Verification ──

    @Test
    fun `a passkey verifies a new session and its counter is recorded`() = runBlocking<Unit> {
        enrol()
        val session = pendingSession()
        val options = service.authenticationOptions(owner.id, session)
        service.authenticate(owner.id, session, authenticator.get(options, handle))
        assertTrue(verified(session))
        assertEquals(authenticator.counter, keys.findByUser(owner.id).single().signatureCount)
        assertNotNull(keys.findByUser(owner.id).single().lastUsedAt)
    }

    @Test
    fun `verification refuses forgeries`() = runBlocking<Unit> {
        enrol()
        val stranger = SoftAuthenticator("http://localhost:3000", "localhost")
        val forgeries: List<(String) -> String> = listOf(
            { o -> authenticator.get(o, handle, SoftAuthenticator.Tweaks(origin = "https://evil.example")) },
            { o -> authenticator.get(o, handle, SoftAuthenticator.Tweaks(rpId = "evil.example")) },
            { o -> authenticator.get(o, handle, SoftAuthenticator.Tweaks(userVerified = false)) },
            { o -> authenticator.get(o, handle, SoftAuthenticator.Tweaks(signWith = stranger.keys)) },
            { o -> authenticator.get(o, handle, SoftAuthenticator.Tweaks(type = "webauthn.create")) },
            { o -> authenticator.get(o, handle, SoftAuthenticator.Tweaks(challenge = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")) },
            { o -> stranger.get(o, handle) }, // a passkey that was never registered
            { o -> authenticator.get(o, ByteArray(16), SoftAuthenticator.Tweaks()) }, // someone else's user handle
        )
        for ((i, forge) in forgeries.withIndex()) {
            val session = pendingSession()
            val options = service.authenticationOptions(owner.id, session)
            assertFailsWith<PasskeyException.Rejected>("forgery $i") { service.authenticate(owner.id, session, forge(options)) }
            assertFalse(verified(session), "forgery $i must not verify the session")
        }
    }

    @Test
    fun `a signature counter that goes backwards is refused (a cloned authenticator)`() = runBlocking<Unit> {
        enrol()
        val s1 = pendingSession()
        service.authenticate(owner.id, s1, authenticator.get(service.authenticationOptions(owner.id, s1), handle, SoftAuthenticator.Tweaks(counter = 10)))
        val s2 = pendingSession()
        val options = service.authenticationOptions(owner.id, s2)
        assertFailsWith<PasskeyException.Rejected> { service.authenticate(owner.id, s2, authenticator.get(options, handle, SoftAuthenticator.Tweaks(counter = 5))) }
    }

    @Test
    fun `an assertion works once, a replay finds no ceremony`() = runBlocking<Unit> {
        enrol()
        val session = pendingSession()
        val assertion = authenticator.get(service.authenticationOptions(owner.id, session), handle)
        service.authenticate(owner.id, session, assertion)
        assertFailsWith<PasskeyException.NoCeremony> { service.authenticate(owner.id, session, assertion) }
    }

    @Test
    fun `an assertion for one session can't verify another`() = runBlocking<Unit> {
        enrol()
        val mine = pendingSession()
        val theirs = pendingSession()
        val optionsForMine = service.authenticationOptions(owner.id, mine)
        service.authenticationOptions(owner.id, theirs)
        assertFailsWith<PasskeyException.Rejected> { service.authenticate(owner.id, theirs, authenticator.get(optionsForMine, handle)) }
    }

    @Test
    fun `a ceremony expires after five minutes, and the wrong kind is refused`() = runBlocking<Unit> {
        enrol()
        val session = pendingSession()
        val options = service.authenticationOptions(owner.id, session)
        clock.advance(Duration.ofMinutes(6))
        assertFailsWith<PasskeyException.NoCeremony> { service.authenticate(owner.id, session, authenticator.get(options, handle)) }
        assertFailsWith<PasskeyException.NoCeremony> { service.authenticate(owner.id, session, "{}") }
        val verifiedSession = pendingSession()
        service.authenticate(owner.id, verifiedSession, authenticator.get(service.authenticationOptions(owner.id, verifiedSession), handle))
        service.registrationOptions(owner.id, verifiedSession)
        assertFailsWith<PasskeyException.NoCeremony> { service.authenticate(owner.id, verifiedSession, "{}") }
    }

    @Test
    fun `verifying needs a passkey to exist`() = runBlocking<Unit> {
        assertFailsWith<PasskeyException.NoneEnrolled> { service.authenticationOptions(owner.id, pendingSession()) }
        assertFailsWith<PasskeyException.NoneEnrolled> { service.authenticate(owner.id, pendingSession(), "{}") }
    }

    @Test
    fun `another user's or a revoked session can't do the passkey step`() = runBlocking<Unit> {
        val session = pendingSession()
        assertFailsWith<PasskeyException.SignedOut> { service.status(UUID.randomUUID(), session) }
        sessions.revoke(session)
        assertFailsWith<PasskeyException.SignedOut> { service.registrationOptions(owner.id, session) }
    }

    // ── Recovery codes ──

    @Test
    fun `a recovery code verifies a session once and alerts the owner`() = runBlocking<Unit> {
        val code = enrol().recoveryCodes.first()
        sent.clear()
        val session = pendingSession()
        assertEquals(9, service.recover(owner.id, session, code.lowercase().replace("-", " ")))
        assertTrue(verified(session))
        assertEquals(9, assertIs<SecurityAlert.RecoveryCodeUsed>(sent.single()).left)
        assertFailsWith<PasskeyException.BadRecoveryCode> { service.recover(owner.id, pendingSession(), code) }
    }

    @Test
    fun `five failed attempts sign the session out and alert the owner`() = runBlocking<Unit> {
        enrol()
        sent.clear()
        val session = pendingSession()
        repeat(4) { assertFailsWith<PasskeyException.BadRecoveryCode> { service.recover(owner.id, session, "AAAA-AAAA-AAAA-AAAA") } }
        assertFailsWith<PasskeyException.TooManyAttempts> { service.recover(owner.id, session, "AAAA-AAAA-AAAA-AAAA") }
        assertEquals(false, sessions.findById(session)?.isActive)
        assertIs<SecurityAlert.SecondFactorFailed>(sent.single())
    }

    @Test
    fun `failed passkey checks count towards the same limit`() = runBlocking<Unit> {
        enrol()
        val session = pendingSession()
        repeat(4) {
            val o = service.authenticationOptions(owner.id, session)
            assertFailsWith<PasskeyException.Rejected> { service.authenticate(owner.id, session, authenticator.get(o, handle, SoftAuthenticator.Tweaks(userVerified = false))) }
        }
        val o = service.authenticationOptions(owner.id, session)
        assertFailsWith<PasskeyException.TooManyAttempts> { service.authenticate(owner.id, session, authenticator.get(o, handle, SoftAuthenticator.Tweaks(userVerified = false))) }
    }

    @Test
    fun `new recovery codes need a verified session and replace the old ones`() = runBlocking<Unit> {
        val session = pendingSession()
        val old = enrol(session).recoveryCodes
        assertFailsWith<PasskeyException.NotAllowed> { service.regenerateRecoveryCodes(owner.id, pendingSession()) }
        val fresh = service.regenerateRecoveryCodes(owner.id, session)
        assertEquals(10, fresh.size)
        assertTrue(fresh.intersect(old.toSet()).isEmpty())
        assertFailsWith<PasskeyException.BadRecoveryCode> { service.recover(owner.id, pendingSession(), old.first()) }
        assertEquals(9, service.recover(owner.id, pendingSession(), fresh.first()))
    }

    @Test
    fun `recovery codes are normalised and look-alikes folded`() {
        assertEquals("0123456789ABCDEF", PasskeyService.normaliseCode("0123-4567-89ab-cdef"))
        assertEquals("0111ABCDEFGHJKMN", PasskeyService.normaliseCode("oIL1 abcd efgh jkmn"))
        assertNull(PasskeyService.normaliseCode("too-short"))
        assertNull(PasskeyService.normaliseCode("UUUU-UUUU-UUUU-UUUU"), "U is not in the alphabet")
        assertNull(PasskeyService.normaliseCode("<script>alert(1)</script>"))
    }

    @Test
    fun `the step after sign-in is enrol until a passkey exists`() = runBlocking<Unit> {
        assertEquals(SecondFactorStep.ENROL, service.stepAfterSignIn(owner.id))
        enrol()
        assertEquals(SecondFactorStep.VERIFY, service.stepAfterSignIn(owner.id))
    }

    @Test
    fun `the relying party is exactly APP_URL's host and origin`() {
        val rp = YubicoWebAuthn("https://AdminOS.example.in/")
        assertEquals("adminos.example.in", rp.rpId)
        assertEquals("https://adminos.example.in", rp.origin)
        assertEquals("http://localhost:3000", YubicoWebAuthn("http://localhost:3000").origin)
        assertFailsWith<IllegalArgumentException> { YubicoWebAuthn("not a url") }
    }

    @Test
    fun `a passkey model refuses impossible values`() {
        assertFailsWith<IllegalArgumentException> { Passkey(userId = owner.id, credentialId = "", publicKeyCose = "x", signatureCount = 0, name = "n", createdAt = Instant.now()) }
        assertFailsWith<IllegalArgumentException> { Passkey(userId = owner.id, credentialId = "x", publicKeyCose = "x", signatureCount = -1, name = "n", createdAt = Instant.now()) }
        assertFailsWith<IllegalArgumentException> { Passkey(userId = owner.id, credentialId = "x", publicKeyCose = "x", signatureCount = 0, name = "n".repeat(61), createdAt = Instant.now()) }
    }
}
