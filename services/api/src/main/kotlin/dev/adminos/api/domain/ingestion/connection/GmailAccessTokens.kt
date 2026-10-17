package dev.adminos.api.domain.ingestion.connection

import dev.adminos.api.infrastructure.crypto.TokenEncryptor
import org.slf4j.LoggerFactory
import java.security.GeneralSecurityException
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * A usable Gmail access token for a connection, for the API's own Gmail calls (AOS-405, D-63).
 *
 * - Stored tokens are decrypted with their row/field context (TokenEncryptor v2, AOS-608).
 * - A token expiring within [EARLY] (or with no known expiry) is refreshed first; the new one is
 *   stored **encrypted** with its expiry, touching only those two columns.
 * - A refused refresh token puts the connection in `error` with the worker's revocation reason
 *   (D-59), so renewals stop and the console asks to reconnect.
 *
 * The Go worker refreshes the same tokens for syncs. Both write a valid token, so whichever is
 * stored last is fine (Google keeps several access tokens valid at once).
 */
class GmailAccessTokens(
    private val connections: ConnectionRepository,
    private val refresher: GmailTokenRefresher,
    private val encryptor: TokenEncryptor?,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = LoggerFactory.getLogger(GmailAccessTokens::class.java)

    /** The stored access token, refreshed first when it is about to expire. */
    suspend fun current(connection: UserConnection): String {
        val expiresAt = connection.tokenExpiresAt
        val stored = connection.accessToken
        if (stored != null && expiresAt != null && expiresAt > clock.instant().plus(EARLY)) {
            return decrypt(stored, connection.id, ACCESS)
        }
        return refreshed(connection)
    }

    /** A new access token from the refresh token, stored encrypted. */
    suspend fun refreshed(connection: UserConnection): String {
        val refreshToken = decrypt(connection.refreshToken ?: throw GmailAccessException.Unreadable(), connection.id, REFRESH)
        val fresh = try {
            refresher.refresh(refreshToken)
        } catch (e: GmailAccessException.Revoked) {
            connections.markError(connection.id, GmailAccessException.REVOKED_REASON)
            logger.warn("Gmail grant revoked for connection {}: marked error", connection.id)
            throw e
        }
        connections.updateAccessToken(connection.id, encrypt(fresh.accessToken, connection.id), fresh.expiresAt)
        return fresh.accessToken
    }

    private fun decrypt(value: String, connectionId: UUID, field: String): String {
        val key = encryptor ?: return value
        return try {
            key.decrypt(value, context(connectionId, field))
        } catch (e: GeneralSecurityException) {
            throw GmailAccessException.Unreadable(e)
        } catch (e: IllegalArgumentException) {
            throw GmailAccessException.Unreadable(e)
        } catch (e: IllegalStateException) {
            throw GmailAccessException.Unreadable(e)
        }
    }

    private fun encrypt(token: String, connectionId: UUID) = encryptor?.encrypt(token, context(connectionId, ACCESS)) ?: token

    private fun context(connectionId: UUID, field: String) = connectionTokenContext(connectionId, field)

    companion object {
        /** Refresh this long before expiry, so a token never lapses mid-call. */
        val EARLY: Duration = Duration.ofMinutes(2)
        private const val ACCESS = "access_token"
        private const val REFRESH = "refresh_token"
    }
}
