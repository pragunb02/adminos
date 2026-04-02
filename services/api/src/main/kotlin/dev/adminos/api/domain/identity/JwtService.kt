package dev.adminos.api.domain.identity

import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.adminos.api.config.AuthConfig
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.*

data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long
)

data class JwtClaims(
    val userId: UUID,
    val email: String,
    val role: String
)

class JwtService(private val config: AuthConfig) {

    init {
        // RFC 7518 §3.2: HS256 key MUST be at least 32 bytes
        require(config.jwtSecret.toByteArray().size >= 32) {
            "JWT_SECRET must be at least 32 bytes (256 bits) for HS256. " +
            "Current length: ${config.jwtSecret.toByteArray().size} bytes. " +
            "Generate one with: openssl rand -base64 32"
        }
    }

    private val secretBytes = config.jwtSecret.toByteArray().copyOf(32)
    private val signer = MACSigner(secretBytes)
    private val verifier = MACVerifier(secretBytes)
    private val random = SecureRandom()

    fun generateTokenPair(user: User): TokenPair {
        val accessToken = generateAccessToken(user)
        val refreshToken = generateRefreshToken()
        return TokenPair(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = config.accessTokenTtlSeconds
        )
    }

    fun generateAccessToken(user: User): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .subject(user.id.toString())
            .claim("email", user.email)
            .claim("role", user.role.name.lowercase())
            .issuer(config.jwtIssuer)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(config.accessTokenTtlSeconds)))
            .build()

        val header = JWSHeader.Builder(JWSAlgorithm.HS256).build()
        val signedJwt = SignedJWT(header, claims)
        signedJwt.sign(signer)
        return signedJwt.serialize()
    }

    /** Verify a JWT access token and extract claims. Returns null if invalid or expired. */
    fun verifyAccessToken(token: String): JwtClaims? {
        return try {
            val signedJwt = SignedJWT.parse(token)
            if (!signedJwt.verify(verifier)) return null

            val claims = signedJwt.jwtClaimsSet
            if (claims.expirationTime.before(Date())) return null

            JwtClaims(
                userId = UUID.fromString(claims.subject),
                email = claims.getStringClaim("email"),
                role = claims.getStringClaim("role")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun generateRefreshToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        /** Hash a refresh token for storage — never store raw tokens */
        fun hashToken(token: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(token.toByteArray())
            return hash.joinToString("") { "%02x".format(it) }
        }
    }
}
