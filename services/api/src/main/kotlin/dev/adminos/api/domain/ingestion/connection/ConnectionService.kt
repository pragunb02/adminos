package dev.adminos.api.domain.ingestion.connection

import dev.adminos.api.domain.ingestion.sync.IngestionService
import dev.adminos.api.domain.ingestion.sync.SyncSession
import dev.adminos.api.domain.ingestion.sync.SyncSessionRepository
import dev.adminos.api.domain.ingestion.sync.SyncSessionStatus
import dev.adminos.api.domain.ingestion.sync.SyncType
import dev.adminos.api.infrastructure.crypto.TokenEncryptor
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * Connections to data sources. [ingestion] queues Gmail syncs for the Go worker (AOS-401): the first
 * sync after connecting Gmail, and manual syncs. Without it (some tests) nothing is queued.
 * [gmailWatch] registers Gmail push after connecting and stops it on disconnect (AOS-405).
 */
class ConnectionService(
    private val connectionRepository: ConnectionRepository,
    private val syncSessionRepository: SyncSessionRepository,
    private val tokenEncryptor: TokenEncryptor? = null,
    private val ingestion: IngestionService? = null,
    private val gmailWatch: GmailWatchService? = null
) {
    private val logger = LoggerFactory.getLogger(ConnectionService::class.java)

    init {
        if (tokenEncryptor == null) {
            logger.warn("TokenEncryptor not configured — OAuth tokens will be stored in PLAINTEXT. " +
                "Set TOKEN_ENCRYPTION_KEY env var for production.")
        }
    }

    suspend fun createConnection(
        userId: UUID,
        sourceType: SourceType,
        gmailAddress: String? = null,
        accessToken: String? = null,
        refreshToken: String? = null
    ): UserConnection =
        upsert(userId, sourceType, gmailAddress, accessToken?.let { OAuthGrant(it, refreshToken, expiresAt = null, scopes = emptyList()) })

    /**
     * Connects Gmail from a Google OAuth grant (AOS-401). The grant must include read access to Gmail
     * and a refresh token (offline access), or the worker could not sync after the first hour.
     * Tokens are stored encrypted with their expiry and scopes; the first (full) sync is queued, then
     * Gmail push is registered (AOS-405). A failed registration is recorded and retried by the renewal
     * job; it never fails the connect.
     */
    suspend fun connectGmail(userId: UUID, gmailAddress: String, grant: OAuthGrant): UserConnection {
        if (GMAIL_READONLY_SCOPE !in grant.scopes) throw GmailGrantException.ReadAccessMissing()
        if (grant.refreshToken.isNullOrBlank()) throw GmailGrantException.OfflineAccessMissing()
        val existing = connectionRepository.findByUserAndSource(userId, SourceType.GMAIL)
        val connection = upsert(userId, SourceType.GMAIL, gmailAddress, grant)
        if (existing?.status != ConnectionStatus.CONNECTED) {
            ingestion?.enqueueGmailIngest(userId, connection.id, syncType = SyncType.HISTORICAL)
            when (val outcome = gmailWatch?.register(connection)) {
                is WatchOutcome.Registered -> return connection.copy(watch = outcome.watch)
                else -> Unit
            }
        }
        return connection
    }

    private suspend fun upsert(userId: UUID, sourceType: SourceType, gmailAddress: String?, grant: OAuthGrant?): UserConnection {
        val existing = connectionRepository.findByUserAndSource(userId, sourceType)
        if (existing != null && existing.status == ConnectionStatus.CONNECTED) {
            return existing
        }
        if (existing != null) {
            // Reconnecting after a disconnect reuses the row (AOS-613): a new row broke uq_conn_source.
            val reactivated = withGrant(existing.copy(
                status = ConnectionStatus.CONNECTED,
                gmailAddress = gmailAddress ?: existing.gmailAddress,
                historyId = null,
                lastError = null,
                watch = GmailWatch.NONE
            ), grant)
            logger.info("Connection reactivated: user={}, source={}", userId, sourceType)
            return connectionRepository.update(reactivated)
        }

        val connection = withGrant(UserConnection(
            userId = userId,
            sourceType = sourceType,
            status = ConnectionStatus.CONNECTED,
            gmailAddress = gmailAddress,
            createdAt = Instant.now()
        ), grant)

        val saved = connectionRepository.save(connection)
        logger.info("Connection created: user={}, source={}", userId, sourceType)
        return saved
    }

    private fun withGrant(connection: UserConnection, grant: OAuthGrant?) = connection.copy(
        accessToken = grant?.let { encryptToken(it.accessToken, tokenContext(connection.id, "access_token")) },
        refreshToken = grant?.refreshToken?.let { encryptToken(it, tokenContext(connection.id, "refresh_token")) },
        tokenExpiresAt = grant?.expiresAt,
        oauthScope = grant?.scopes?.takeIf { it.isNotEmpty() }
    )

    suspend fun disconnect(userId: UUID, connectionId: UUID): UserConnection {
        val connection = connectionRepository.findById(connectionId)
            ?: throw ConnectionNotFoundException()

        if (connection.userId != userId) {
            throw ConnectionNotFoundException()
        }

        // Stop Gmail push while the tokens still exist (best effort, AOS-405); then forget the watch.
        gmailWatch?.stop(connection)
        val updated = connectionRepository.update(connection.copy(
            status = ConnectionStatus.DISCONNECTED,
            accessToken = null,
            refreshToken = null,
            watch = GmailWatch.NONE
        ))

        logger.info("Connection disconnected: user={}, source={}", userId, connection.sourceType)
        return updated
    }

    /** A manual sync: for Gmail it is queued for the worker (AOS-401); other sources only record it. */
    suspend fun triggerManualSync(userId: UUID, connectionId: UUID): SyncSession {
        val connection = connectionRepository.findById(connectionId)
            ?: throw ConnectionNotFoundException()

        if (connection.userId != userId) {
            throw ConnectionNotFoundException()
        }

        if (connection.status != ConnectionStatus.CONNECTED) {
            throw ConnectionNotConnectedException()
        }

        if (connection.sourceType == SourceType.GMAIL && ingestion != null) {
            logger.info("Manual Gmail sync queued: user={}, connection={}", userId, connectionId)
            return ingestion.enqueueGmailIngest(userId, connectionId, syncType = SyncType.MANUAL)
        }

        val session = SyncSession(
            userId = userId,
            connectionId = connectionId,
            syncType = SyncType.MANUAL,
            status = SyncSessionStatus.QUEUED
        )

        syncSessionRepository.save(session)
        logger.info("Manual sync triggered: user={}, connection={}", userId, connectionId)
        return session
    }

    suspend fun getUserConnections(userId: UUID): List<UserConnection> {
        return connectionRepository.findByUserId(userId)
    }

    suspend fun getConnection(userId: UUID, connectionId: UUID): UserConnection {
        val connection = connectionRepository.findById(connectionId)
            ?: throw ConnectionNotFoundException()
        if (connection.userId != userId) throw ConnectionNotFoundException()
        return connection
    }

    suspend fun findByGmailAddress(gmailAddress: String): UserConnection? {
        return connectionRepository.findByGmailAddress(gmailAddress)
    }

    suspend fun findConnectedBySourceType(sourceType: SourceType): List<UserConnection> {
        return connectionRepository.findConnectedBySourceType(sourceType)
    }

    suspend fun getOrCreateConnection(userId: UUID, sourceType: SourceType): UserConnection {
        return connectionRepository.findByUserAndSource(userId, sourceType)
            ?: createConnection(userId, sourceType)
    }

    /** Where a token is stored: bound into its ciphertext so it can't be moved to another row or field (AOS-608). */
    internal fun tokenContext(connectionId: UUID, field: String) = connectionTokenContext(connectionId, field)

    private fun encryptToken(token: String, context: String): String {
        return tokenEncryptor?.encrypt(token, context) ?: token
    }
}

/** The TokenEncryptor context of a stored token (AOS-608); the Go worker uses the same (D-59). */
internal fun connectionTokenContext(connectionId: UUID, field: String) = "user_connections:$connectionId:$field"

/** Google's read-only Gmail scope: the only Gmail access AdminOS asks for (AOS-401). */
const val GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"

/** What Google granted at sign-in, as the domain sees it: tokens in plain text (encrypted on save). */
data class OAuthGrant(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Instant?,
    val scopes: List<String>
) {
    init {
        require(accessToken.isNotBlank()) { "An OAuth grant needs an access token" }
    }

    override fun toString() = "OAuthGrant(expiresAt=$expiresAt, scopes=$scopes)" // never print tokens

    companion object {
        /** From Google's token response: `expires_in` seconds from [now]; `scope` space-separated. */
        fun fromGoogle(accessToken: String, refreshToken: String?, expiresInSeconds: Int?, scope: String?, now: Instant) = OAuthGrant(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = expiresInSeconds?.let { now.plusSeconds(it.toLong()) },
            scopes = scope.orEmpty().split(' ').filter { it.isNotBlank() }
        )
    }
}

/** Why a Google grant can't connect Gmail; the route maps each to a 400. */
sealed class GmailGrantException(message: String) : RuntimeException(message) {
    class ReadAccessMissing : GmailGrantException("Gmail read access was not granted")
    class OfflineAccessMissing : GmailGrantException("Google did not grant offline access (no refresh token)")
}

class ConnectionNotFoundException : RuntimeException("Connection not found")
class ConnectionNotConnectedException : RuntimeException("Connection is not in connected state")
