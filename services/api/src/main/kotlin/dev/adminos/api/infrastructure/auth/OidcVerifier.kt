package dev.adminos.api.infrastructure.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Verifies Google OIDC tokens from Pub/Sub push subscriptions.
 * Uses Nimbus JOSE+JWT to validate JWTs against Google's JWKS endpoint.
 */
class OidcVerifier(private val audience: String) {
    private val logger = LoggerFactory.getLogger(OidcVerifier::class.java)

    private val jwtProcessor: DefaultJWTProcessor<SecurityContext> by lazy {
        buildProcessor()
    }

    /**
     * Verifies the given OIDC bearer token.
     * @param token The JWT bearer token (without "Bearer " prefix)
     * @return The verified claims set
     * @throws OidcVerificationException if verification fails
     */
    fun verify(token: String): JWTClaimsSet {
        return try {
            jwtProcessor.process(token, null)
        } catch (e: Exception) {
            logger.warn("OIDC token verification failed: ${e.message}")
            throw OidcVerificationException("Invalid OIDC token: ${e.message}", e)
        }
    }

    /**
     * Returns true if the verifier is configured with a non-blank audience.
     */
    fun isConfigured(): Boolean = audience.isNotBlank()

    private fun buildProcessor(): DefaultJWTProcessor<SecurityContext> {
        val processor = DefaultJWTProcessor<SecurityContext>()

        val jwkSource = JWKSourceBuilder
            .create<SecurityContext>(URI("https://www.googleapis.com/oauth2/v3/certs").toURL())
            .retrying(true)
            .build()

        processor.jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, jwkSource)

        val expectedClaims = JWTClaimsSet.Builder()
            .audience(audience)
            .build()

        processor.jwtClaimsSetVerifier = DefaultJWTClaimsVerifier(
            expectedClaims,
            setOf("iss", "exp", "iat")
        )

        return processor
    }
}

class OidcVerificationException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
