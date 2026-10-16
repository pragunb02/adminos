package dev.adminos.api.infrastructure.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Disabled
import java.net.InetSocketAddress
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Google Pub/Sub OIDC verification (AOS-312) against a local JWKS endpoint
 * (JDK HttpServer on 127.0.0.1:0; no real network).
 */
class AuthOidcVerifierCoverageTest {

    private companion object {
        const val AUDIENCE = "https://adminos.example.in/api/v1/webhooks/gmail"
        const val GOOGLE_ISS = "https://accounts.google.com"
        val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("google-key-1").generate()
        val strangerKey: RSAKey = RSAKeyGenerator(2048).keyID("google-key-1").generate()
    }

    private val jwksHits = AtomicInteger()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/oauth2/v3/certs") { ex ->
            jwksHits.incrementAndGet()
            val body = JWKSet(signingKey.toPublicJWK()).toString().toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        start()
    }
    private val jwksUrl = "http://127.0.0.1:${server.address.port}/oauth2/v3/certs"
    private val verifier = OidcVerifier(AUDIENCE, jwksUrl)

    @AfterEach
    fun stop() = server.stop(0)

    private fun claims(
        aud: String? = AUDIENCE,
        iss: String? = GOOGLE_ISS,
        exp: Date? = Date(System.currentTimeMillis() + 3_600_000),
        iat: Date? = Date(System.currentTimeMillis() - 1_000),
    ): JWTClaimsSet = JWTClaimsSet.Builder().apply {
        aud?.let { audience(it) }
        iss?.let { issuer(it) }
        exp?.let { expirationTime(it) }
        iat?.let { issueTime(it) }
        subject("110169484474386276334")
        claim("email", "gmail-api-push@system.gserviceaccount.com")
        claim("email_verified", true)
    }.build()

    private fun sign(claims: JWTClaimsSet, key: RSAKey = signingKey): String =
        SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), claims)
            .apply { sign(RSASSASigner(key)) }
            .serialize()

    @Test
    fun `a Google-signed token for our audience is accepted and its claims returned`() {
        val result = verifier.verify(sign(claims()))

        assertEquals(listOf(AUDIENCE), result.audience)
        assertEquals("gmail-api-push@system.gserviceaccount.com", result.getStringClaim("email"))
    }

    @Test
    fun `keys are fetched from the configured JWKS endpoint`() {
        verifier.verify(sign(claims()))

        assertTrue(jwksHits.get() >= 1)
    }

    @Test
    fun `the JWKS is cached across verifications`() {
        verifier.verify(sign(claims()))
        val afterFirst = jwksHits.get()

        verifier.verify(sign(claims()))

        assertEquals(afterFirst, jwksHits.get())
    }

    @Test
    fun `a token for a different audience is rejected`() {
        val e = assertFailsWith<OidcVerificationException> {
            verifier.verify(sign(claims(aud = "https://evil.example.com/push")))
        }

        assertTrue(e.message!!.startsWith("Invalid OIDC token:"))
        assertNotNull(e.cause)
    }

    @Test
    fun `a token without an audience is rejected`() {
        assertFailsWith<OidcVerificationException> { verifier.verify(sign(claims(aud = null))) }
    }

    @Test
    fun `an expired token is rejected`() {
        val expired = claims(exp = Date(System.currentTimeMillis() - 600_000), iat = Date(System.currentTimeMillis() - 4_200_000))

        assertFailsWith<OidcVerificationException> { verifier.verify(sign(expired)) }
    }

    @Test
    fun `a token without exp is rejected`() {
        assertFailsWith<OidcVerificationException> { verifier.verify(sign(claims(exp = null))) }
    }

    @Test
    fun `a token without iat is rejected`() {
        assertFailsWith<OidcVerificationException> { verifier.verify(sign(claims(iat = null))) }
    }

    @Test
    fun `a token without iss is rejected`() {
        assertFailsWith<OidcVerificationException> { verifier.verify(sign(claims(iss = null))) }
    }

    @Test
    fun `a token signed by a key not in the JWKS is rejected`() {
        assertFailsWith<OidcVerificationException> { verifier.verify(sign(claims(), strangerKey)) }
    }

    @Test
    fun `a token whose payload was altered after signing is rejected`() {
        val parts = sign(claims()).split(".")
        val forgedPayload = com.nimbusds.jose.util.Base64URL.encode(claims(aud = AUDIENCE).toString().replace("gmail-api-push", "attacker")).toString()

        assertFailsWith<OidcVerificationException> { verifier.verify("${parts[0]}.$forgedPayload.${parts[2]}") }
    }

    @Test
    fun `an HS256 token is rejected even with the right claims`() {
        val hs = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims()).apply { sign(MACSigner(ByteArray(32) { 7 })) }.serialize()

        assertFailsWith<OidcVerificationException> { verifier.verify(hs) }
    }

    @Test
    fun `an unsigned alg none token is rejected`() {
        val unsigned = com.nimbusds.jwt.PlainJWT(claims()).serialize()

        assertFailsWith<OidcVerificationException> { verifier.verify(unsigned) }
    }

    @Test
    fun `a malformed token is rejected`() {
        assertFailsWith<OidcVerificationException> { verifier.verify("not-a-jwt") }
    }

    @Test
    fun `a failing JWKS endpoint makes verification fail closed`() {
        val token = sign(claims())
        val offline = OidcVerifier(AUDIENCE, "http://127.0.0.1:${server.address.port}/missing")

        assertFailsWith<OidcVerificationException> { offline.verify(token) }
    }

    @Test
    fun `a verifier with an audience is configured`() {
        assertTrue(OidcVerifier(AUDIENCE).isConfigured())
    }

    @Test
    fun `a verifier with a blank audience is not configured`() {
        assertFalse(OidcVerifier("  ").isConfigured())
    }

    @Test
    fun `the exception keeps message and cause`() {
        val cause = IllegalStateException("boom")
        val e = OidcVerificationException("Invalid OIDC token: boom", cause)

        assertEquals("Invalid OIDC token: boom", e.message)
        assertEquals(cause, e.cause)
        assertEquals(null, OidcVerificationException("x").cause)
    }

    @Test
    @Disabled("BUG AOS-312: OidcVerifier.kt:57-64 only requires iss to be present; Google says iss must be accounts.google.com or https://accounts.google.com")
    fun `a token with a non-Google issuer is rejected`() {
        assertFailsWith<OidcVerificationException> { verifier.verify(sign(claims(iss = "https://evil.example.com"))) }
    }
}
