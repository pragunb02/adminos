package dev.adminos.api.domain.ingestion.connection

import dev.adminos.api.infrastructure.crypto.TokenEncryptor
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID

/** What the browser is sent to Google with: the CSRF state and the PKCE S256 challenge. */
data class IssuedOAuthState(val state: String, val codeChallenge: String) {
    override fun toString() = "IssuedOAuthState(…)" // the state is a bearer value until used
}

/** Who may redeem a state, and where Google sent the user back. */
data class OAuthStateClaim(val userId: UUID?, val purpose: OAuthPurpose, val redirectUri: String) {
    init {
        require((userId == null) == (purpose == OAuthPurpose.LOGIN)) { "Only a sign-in state has no user" }
    }

    companion object {
        /** Signing in: nobody is signed in yet (AOS-618). */
        fun login(redirectUri: String) = OAuthStateClaim(null, OAuthPurpose.LOGIN, redirectUri)
    }
}

/**
 * OAuth `state` (CSRF) and PKCE for Google consents (AOS-406, D-64).
 *
 * - **Issue:** 32 random bytes (base64url) bound to the signed-in user, the purpose and the redirect
 *   URI, valid for [ttl] (10 min). Stored server-side as a SHA-256 hash, so no signing secret is
 *   needed and a database copy can't be replayed. A PKCE verifier (32 random bytes) is stored
 *   encrypted; Google gets its S256 challenge.
 * - **Consume:** exactly once. An unknown state, or one issued to another user, purpose or
 *   redirect URI, is [OAuthStateException.Mismatch]; a used one [OAuthStateException.Reused]; an
 *   old one [OAuthStateException.Expired]. A mismatch never uses up the real owner's state.
 * - **Housekeeping:** each issue deletes states that expired over a day ago (kept that long so an
 *   old link reads "expired", not "doesn't match").
 */
class OAuthStateService(
    private val states: OAuthStateRepository,
    private val encryptor: TokenEncryptor?,
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofMinutes(10)
) {
    private val random = SecureRandom()

    init {
        require(ttl > Duration.ZERO && ttl <= Duration.ofHours(1)) { "An OAuth state lives at most an hour, was $ttl" }
    }

    suspend fun issue(claim: OAuthStateClaim): IssuedOAuthState {
        val now = clock.instant()
        states.deleteExpiredBefore(now.minus(KEEP_EXPIRED))
        val state = randomToken()
        val verifier = randomToken()
        val id = UUID.randomUUID()
        states.save(OAuthState(
            id = id,
            userId = claim.userId,
            purpose = claim.purpose,
            stateHash = sha256Hex(state),
            redirectUri = claim.redirectUri,
            codeVerifier = encryptor?.encrypt(verifier, verifierContext(id)) ?: verifier,
            createdAt = now,
            expiresAt = now.plus(ttl)
        ))
        return IssuedOAuthState(state, codeChallenge(verifier))
    }

    /** Checks [state] against [claim] and uses it up; returns the PKCE verifier for the token exchange. */
    suspend fun consume(state: String?, claim: OAuthStateClaim): String {
        if (state.isNullOrBlank() || state.length > MAX_STATE_LENGTH) throw OAuthStateException.Mismatch()
        val stored = states.findByHash(sha256Hex(state)) ?: throw OAuthStateException.Mismatch()
        if (stored.userId != claim.userId || stored.purpose != claim.purpose || stored.redirectUri != claim.redirectUri) {
            throw OAuthStateException.Mismatch()
        }
        if (stored.usedAt != null) throw OAuthStateException.Reused()
        val now = clock.instant()
        if (stored.expiresAt <= now) throw OAuthStateException.Expired()
        if (!states.markUsed(stored.id, now)) throw OAuthStateException.Reused()
        return decryptVerifier(stored)
    }

    private fun decryptVerifier(stored: OAuthState): String {
        val key = encryptor ?: return stored.codeVerifier
        return try {
            key.decrypt(stored.codeVerifier, verifierContext(stored.id))
        } catch (e: GeneralSecurityException) {
            throw OAuthStateException.Mismatch() // sealed with another key: treat as not ours
        } catch (e: IllegalArgumentException) {
            throw OAuthStateException.Mismatch()
        }
    }

    private fun randomToken(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    private fun verifierContext(id: UUID) = "oauth_states:$id:code_verifier"

    companion object {
        private val KEEP_EXPIRED: Duration = Duration.ofDays(1)
        private const val MAX_STATE_LENGTH = 128

        fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /** PKCE S256 (RFC 7636): base64url(SHA-256(verifier)) without padding. */
        fun codeChallenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }
}
