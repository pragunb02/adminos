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
import java.time.ZoneId
import java.util.*

data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long
)

data class JwtClaims(
    val userId: UUID,
    val email: String,
    val role: String,
    /** The user's timezone for day boundaries (AOS-120); tokens issued before it existed have none. */
    val timezone: String? = null
)

/** Claims of a scoped device token (AOS-203). [tokenId] is the revocable `device_tokens` row. */
data class DeviceTokenClaims(
    val userId: UUID,
    val tokenId: UUID,
    val scopes: Set<String>
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
            .claim("typ", TYPE_ACCESS)
            .claim("email", user.email)
            .claim("role", user.role.name.lowercase())
            .claim("tz", validZoneOrNull(user.timezone))
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
            if (claims.issuer != config.jwtIssuer) return null
            // Only access tokens: a device token must never work as a login (tokens before `typ` existed are access tokens).
            if ((claims.getStringClaim("typ") ?: TYPE_ACCESS) != TYPE_ACCESS) return null

            JwtClaims(
                userId = UUID.fromString(claims.subject),
                email = claims.getStringClaim("email"),
                role = claims.getStringClaim("role"),
                timezone = claims.getStringClaim("tz")
            )
        } catch (e: Exception) {
            null
        }
    }

    /** A long-lived, scoped token for a device (e.g. the Mac SMS reader). Revocation is checked by the caller. */
    fun generateDeviceToken(userId: UUID, tokenId: UUID, scopes: Set<String>, ttl: java.time.Duration): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .subject(userId.toString())
            .jwtID(tokenId.toString())
            .claim("typ", TYPE_DEVICE)
            .claim("scope", scopes.sorted().joinToString(" "))
            .issuer(config.jwtIssuer)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(ttl)))
            .build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims).apply { sign(signer) }.serialize()
    }

    /** Verifies signature, issuer, expiry and type. Returns null for anything that isn't a valid device token. */
    fun verifyDeviceToken(token: String): DeviceTokenClaims? = try {
        val jwt = SignedJWT.parse(token)
        val c = jwt.jwtClaimsSet
        if (!jwt.verify(verifier) || c.expirationTime?.before(Date()) != false || c.issuer != config.jwtIssuer ||
            c.getStringClaim("typ") != TYPE_DEVICE) null
        else DeviceTokenClaims(
            userId = UUID.fromString(c.subject),
            tokenId = UUID.fromString(c.jwtid),
            scopes = c.getStringClaim("scope").orEmpty().split(' ').filter { it.isNotBlank() }.toSet()
        )
    } catch (e: Exception) {
        null
    }

    private fun generateRefreshToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val TYPE_ACCESS = "access"
        const val TYPE_DEVICE = "device"

        /** Hash a refresh token for storage — never store raw tokens */
        fun hashToken(token: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(token.toByteArray())
            return hash.joinToString("") { "%02x".format(it) }
        }
    }
}

/** A stored timezone that the JDK knows, else null (the caller then uses the default zone, logged). */
internal fun validZoneOrNull(id: String?): String? =
    id?.takeIf { runCatching { ZoneId.of(it) }.isSuccess }
        ?: id?.let { org.slf4j.LoggerFactory.getLogger("JwtService").warn("Unknown timezone '{}' on a user; using the default zone", it); null }
