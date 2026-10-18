package dev.adminos.api.domain.identity

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.config.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import kotlin.test.*

/** AOS-312: JwtService claim checks, device-token rules and their routes' edge cases. */
class IdentityJwtAndDeviceCoverageTest {

    private val config = identityTestAuthConfig()
    private val jwt = JwtService(config)
    private val now = Instant.parse("2026-10-16T06:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val tokens = InMemoryDeviceTokenRepository()
    private val devices = DeviceTokenService(tokens, jwt, clock)
    private val userId = UUID.randomUUID()

    /** Signs arbitrary claims with the service's own secret, to reach each claim check. */
    private fun signed(build: JWTClaimsSet.Builder.() -> Unit): String {
        val claims = JWTClaimsSet.Builder().apply(build).build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims).apply { sign(MACSigner(config.jwtSecret.toByteArray())) }.serialize()
    }

    private fun deviceClaims(tokenId: UUID = UUID.randomUUID()): JWTClaimsSet.Builder.() -> Unit = {
        subject(userId.toString()); jwtID(tokenId.toString()); claim("typ", "device"); claim("scope", Scopes.INGEST_SMS)
        issuer(config.jwtIssuer); expirationTime(Date.from(Instant.now().plusSeconds(600)))
    }

    // --- JwtService ---

    @Test
    fun `a jwt secret shorter than 32 bytes is refused at start-up`() {
        val e = assertFailsWith<IllegalArgumentException> { JwtService(identityTestAuthConfig(jwtSecret = "too-short")) }
        assertTrue("at least 32 bytes" in e.message.orEmpty())
    }

    @Test
    fun `an access token without a typ claim is accepted as a legacy access token`() {
        val token = signed {
            subject(userId.toString()); claim("email", "me@example.com"); claim("role", "owner")
            issuer(config.jwtIssuer); expirationTime(Date.from(Instant.now().plusSeconds(600)))
        }
        assertEquals(userId, jwt.verifyAccessToken(token)?.userId)
    }

    @Test
    fun `an access token from another issuer is rejected`() {
        val token = signed {
            subject(userId.toString()); claim("typ", "access"); claim("email", "me@example.com"); claim("role", "owner")
            issuer("someone-else"); expirationTime(Date.from(Instant.now().plusSeconds(600)))
        }
        assertNull(jwt.verifyAccessToken(token))
    }

    @Test
    fun `a device token without an expiry is rejected`() {
        assertNull(jwt.verifyDeviceToken(signed { deviceClaims()(this); expirationTime(null) }))
    }

    @Test
    fun `an expired device token is rejected`() {
        assertNull(jwt.verifyDeviceToken(signed { deviceClaims()(this); expirationTime(Date.from(Instant.now().minusSeconds(1))) }))
    }

    @Test
    fun `a device token with a bad signature is rejected`() {
        val other = JwtService(identityTestAuthConfig(jwtSecret = "another-secret-that-is-also-32-bytes-long"))
        assertNull(jwt.verifyDeviceToken(other.generateDeviceToken(userId, UUID.randomUUID(), setOf(Scopes.INGEST_SMS), Duration.ofDays(1))))
    }

    @Test
    fun `a device token without a scope claim verifies with no scopes`() {
        val claims = assertNotNull(jwt.verifyDeviceToken(signed { deviceClaims()(this); claim("scope", null) }))
        assertEquals(emptySet(), claims.scopes)
    }

    @Test
    fun `a malformed device token is rejected`() {
        assertNull(jwt.verifyDeviceToken("not.a.jwt"))
    }

    @Test
    fun `an unknown timezone is dropped and a missing one stays missing`() {
        assertNull(validZoneOrNull("Mars/Olympus_Mons"))
        assertNull(validZoneOrNull(null))
        assertEquals("Asia/Kolkata", validZoneOrNull("Asia/Kolkata"))
    }

    // --- DeviceToken rules ---

    @Test
    fun `a device token needs a non-blank name of at most 100 characters`() {
        assertFailsWith<IllegalArgumentException> { DeviceToken(userId = userId, name = "  ", scopes = setOf(Scopes.INGEST_SMS), expiresAt = now) }
        assertFailsWith<IllegalArgumentException> { DeviceToken(userId = userId, name = "x".repeat(101), scopes = setOf(Scopes.INGEST_SMS), expiresAt = now) }
    }

    @Test
    fun `a device token needs at least one scope`() {
        assertFailsWith<IllegalArgumentException> { DeviceToken(userId = userId, name = "My Mac", scopes = emptySet(), expiresAt = now) }
    }

    @Test
    fun `issuing trims the name and expires after the ttl`() = runBlocking<Unit> {
        val issued = devices.issue(userId, "  Owner's MacBook  ", setOf(Scopes.INGEST_SMS))

        assertEquals("Owner's MacBook", issued.record.name)
        assertEquals(now.plus(Duration.ofDays(365)), issued.record.expiresAt)
    }

    @Test
    fun `revoking an already revoked token keeps the first revocation time`() = runBlocking<Unit> {
        val issued = devices.issue(userId, "My Mac", setOf(Scopes.INGEST_SMS))
        assertTrue(devices.revoke(userId, issued.record.id))
        val later = DeviceTokenService(tokens, jwt, Clock.fixed(now.plusSeconds(3600), ZoneOffset.UTC))

        assertTrue(later.revoke(userId, issued.record.id))
        assertEquals(now, tokens.findById(issued.record.id)?.revokedAt)
    }

    @Test
    fun `a device token whose subject is not the row's owner does not authenticate`() = runBlocking<Unit> {
        val issued = devices.issue(userId, "My Mac", setOf(Scopes.INGEST_SMS))
        val forged = jwt.generateDeviceToken(UUID.randomUUID(), issued.record.id, setOf(Scopes.INGEST_SMS), Duration.ofDays(1))

        assertNull(devices.authenticate(forged, Scopes.INGEST_SMS))
    }

    @Test
    fun `a valid device token authenticates and records its use`() = runBlocking<Unit> {
        val issued = devices.issue(userId, "My Mac", setOf(Scopes.INGEST_SMS))

        val principal = assertNotNull(devices.authenticate(issued.token, Scopes.INGEST_SMS))

        assertEquals(DevicePrincipal(userId, issued.record.id, setOf(Scopes.INGEST_SMS)), principal)
        assertEquals(now, tokens.findById(issued.record.id)?.lastUsedAt)
    }

    @Test
    fun `a device token response shows when the token was last used and revoked`() {
        val record = DeviceToken(userId = userId, name = "My Mac", scopes = setOf(Scopes.INGEST_SMS), createdAt = now, expiresAt = now.plusSeconds(60),
            lastUsedAt = now.plusSeconds(10), revokedAt = now.plusSeconds(20))

        val res = DeviceTokenResponse.from(record)

        assertEquals("2026-10-16T06:00:10Z", res.lastUsedAt)
        assertEquals("2026-10-16T06:00:20Z", res.revokedAt)
        assertNull(res.token)
    }

    // --- Device routes ---

    private fun deviceRoutesTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { this.config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwt)
            routing {
                route("/api/v1") {
                    deviceTokenRoutes(devices)
                    // A route that reads the device principal without a device auth provider in front of it.
                    get("/unguarded-device") { call.respondText(call.devicePrincipal.userId.toString()) }
                }
            }
        }
        block()
    }

    @Test
    fun `revoking a device token with a malformed id is a 400`() = deviceRoutesTest {
        val res = client.delete("/api/v1/device-tokens/not-a-uuid") { bearerAuth(jwt.generateAccessToken(User(id = userId, email = "me@example.com", googleId = "g"))) }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun `revoking someone else's device token is a 404`() = deviceRoutesTest {
        val theirs = devices.issue(UUID.randomUUID(), "Their Mac", setOf(Scopes.INGEST_SMS))
        val res = client.delete("/api/v1/device-tokens/${theirs.record.id}") { bearerAuth(jwt.generateAccessToken(User(id = userId, email = "me@example.com", googleId = "g"))) }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `reading the device principal without device auth is a 401`() = deviceRoutesTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/unguarded-device").status)
    }

    // --- In-memory fakes used across the suite ---

    @Test
    fun `the in-memory user repository finds by email and lists incomplete active onboarding`() = runBlocking<Unit> {
        val repo = InMemoryUserRepository()
        val onboarding = repo.save(User(email = "new@example.com", googleId = "g-new"))
        repo.save(User(email = "done@example.com", googleId = "g-done", onboardingStatus = OnboardingStatus.COMPLETED))
        repo.save(User(email = "inactive@example.com", googleId = "g-off", isActive = false))

        assertEquals(onboarding.id, repo.findByEmail("new@example.com")?.id)
        assertNull(repo.findByEmail("nobody@example.com"))
        assertEquals(listOf(onboarding.id), repo.findByOnboardingIncomplete().map { it.id })
    }

    @Test
    fun `the in-memory user repository update replaces the stored user`() = runBlocking<Unit> {
        val repo = InMemoryUserRepository()
        val u = repo.save(User(email = "me@example.com", googleId = "g-1"))

        val updated = repo.update(u.copy(name = "Owner"))

        assertEquals("Owner", updated.name)
        assertEquals("Owner", repo.findById(u.id)?.name)
        assertNull(repo.findByGoogleId("g-unknown"))
    }
}
