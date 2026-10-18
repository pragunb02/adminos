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

/** Proves a webhook call came from our Pub/Sub push subscription; throws [OidcVerificationException] if not. */
fun interface PushTokenVerifier {
    fun verify(token: String): JWTClaimsSet
}

/**
 * Verifies Google OIDC tokens from Pub/Sub push subscriptions (AOS-609), with Nimbus against Google's JWKS:
 * RS256 signature, our audience, `exp`/`iat`, **Google's issuer**, and **our push service account**
 * (`email` + `email_verified`). Without the service-account check any Google-signed token for our
 * audience — from any Google Cloud project — would pass.
 */
class OidcVerifier(
    private val audience: String,
    private val serviceAccount: String,
    private val jwksUrl: String = "https://www.googleapis.com/oauth2/v3/certs"
) : PushTokenVerifier {
    companion object {
        val GOOGLE_ISSUERS = setOf("accounts.google.com", "https://accounts.google.com")
    }

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
    override fun verify(token: String): JWTClaimsSet {
        val claims = try {
            jwtProcessor.process(token, null)
        } catch (e: Exception) {
            // The library's message can quote the token's claims, which the caller chooses (log injection,
            // AOS-628): only the exception type is logged, and the message passed on is fixed.
            logger.warn("OIDC token verification failed: {}", e.javaClass.simpleName)
            throw OidcVerificationException("Invalid OIDC token", e)
        }
        if (claims.issuer !in GOOGLE_ISSUERS) reject("issuer is not Google")
        val email = runCatching { claims.getStringClaim("email") }.getOrNull()
        val verified = runCatching { claims.getBooleanClaim("email_verified") }.getOrNull()
        if (verified != true) reject("email is not verified")
        if (email == null || !email.equals(serviceAccount, ignoreCase = true)) reject("not our push service account")
        return claims
    }

    private fun reject(why: String): Nothing {
        logger.warn("OIDC token rejected: {}", why)
        throw OidcVerificationException("Invalid OIDC token: $why")
    }

    /** Both the audience and the push service account are needed; without them nothing is accepted. */
    fun isConfigured(): Boolean = audience.isNotBlank() && serviceAccount.isNotBlank()

    private fun buildProcessor(): DefaultJWTProcessor<SecurityContext> {
        val processor = DefaultJWTProcessor<SecurityContext>()

        val jwkSource = JWKSourceBuilder
            .create<SecurityContext>(URI(jwksUrl).toURL())
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
