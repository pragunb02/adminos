package dev.adminos.api.domain.ingestion.connection

import java.time.Instant
import java.util.UUID

/** What an OAuth `state` was issued for; a state is only accepted for the same purpose. */
enum class OAuthPurpose {
    GMAIL_CONNECT,
    /** Signing in with Google (AOS-618, AUD-06): no user yet, so the state is bound to the browser by a cookie instead. */
    LOGIN
}

/**
 * A pending OAuth consent (AOS-406, D-64): the CSRF `state` sent to Google, stored server-side so it
 * can be checked once against the signed-in user who started the consent.
 *
 * - Only the SHA-256 [stateHash] of the state is kept; the state itself lives in the user's browser.
 * - [codeVerifier] is the PKCE verifier, encrypted (TokenEncryptor, context `oauth_states:<id>:code_verifier`).
 * - [usedAt] makes it single-use; [expiresAt] makes it short-lived.
 */
data class OAuthState(
    val id: UUID = UUID.randomUUID(),
    /** Who started the consent; null only for [OAuthPurpose.LOGIN], which starts before anyone is signed in. */
    val userId: UUID?,
    val purpose: OAuthPurpose,
    val stateHash: String,
    val redirectUri: String,
    val codeVerifier: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val usedAt: Instant? = null
) {
    init {
        require(stateHash.matches(Regex("[0-9a-f]{64}"))) { "stateHash is a hex SHA-256" }
        require(redirectUri.isNotBlank()) { "redirectUri is required" }
        require((userId == null) == (purpose == OAuthPurpose.LOGIN)) { "Only a sign-in state has no user" }
        require(expiresAt > createdAt) { "An OAuth state must expire after it is created" }
    }

    override fun toString() = "OAuthState(id=$id, userId=$userId, purpose=$purpose, expiresAt=$expiresAt, usedAt=$usedAt)"
}

interface OAuthStateRepository {
    suspend fun save(state: OAuthState): OAuthState
    suspend fun findByHash(stateHash: String): OAuthState?

    /** Marks the state used at [at] unless it already was; false when it was (single use, atomic). */
    suspend fun markUsed(id: UUID, at: Instant): Boolean

    /** Deletes states that expired before [before]; returns how many. */
    suspend fun deleteExpiredBefore(before: Instant): Int
}

/** Why a presented OAuth state was refused; the route maps each to its own 400 code. */
sealed class OAuthStateException(message: String) : RuntimeException(message) {
    /** No state, an unknown one, or one issued to another user, purpose or redirect URI. */
    class Mismatch : OAuthStateException("The OAuth state doesn't match this sign-in")
    class Expired : OAuthStateException("The OAuth state has expired")
    class Reused : OAuthStateException("The OAuth state was already used")
}
