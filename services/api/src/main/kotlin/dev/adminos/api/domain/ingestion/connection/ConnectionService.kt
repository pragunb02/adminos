package dev.adminos.api.domain.ingestion.connection

import dev.adminos.api.domain.ingestion.sync.SyncSession
import dev.adminos.api.domain.ingestion.sync.SyncSessionRepository
import dev.adminos.api.domain.ingestion.sync.SyncSessionStatus
import dev.adminos.api.domain.ingestion.sync.SyncType
import dev.adminos.api.infrastructure.crypto.TokenEncryptor
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

class ConnectionService(
    private val connectionRepository: ConnectionRepository,
    private val syncSessionRepository: SyncSessionRepository,
    private val tokenEncryptor: TokenEncryptor? = null
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
    ): UserConnection {
        val existing = connectionRepository.findByUserAndSource(userId, sourceType)
        if (existing != null && existing.status == ConnectionStatus.CONNECTED) {
            return existing
        }

        val connection = UserConnection(
            userId = userId,
            sourceType = sourceType,
            status = ConnectionStatus.CONNECTED,
            gmailAddress = gmailAddress,
            accessToken = accessToken?.let { encryptToken(it) },
            refreshToken = refreshToken?.let { encryptToken(it) },
            createdAt = Instant.now()
        )

        val saved = connectionRepository.save(connection)
        logger.info("Connection created: user={}, source={}", userId, sourceType)
        return saved
    }

    suspend fun disconnect(userId: UUID, connectionId: UUID): UserConnection {
        val connection = connectionRepository.findById(connectionId)
            ?: throw ConnectionNotFoundException()

        if (connection.userId != userId) {
            throw ConnectionNotFoundException()
        }

        val updated = connectionRepository.update(connection.copy(
            status = ConnectionStatus.DISCONNECTED,
            accessToken = null,
            refreshToken = null
        ))

        logger.info("Connection disconnected: user={}, source={}", userId, connection.sourceType)
        return updated
    }

    suspend fun triggerManualSync(userId: UUID, connectionId: UUID): SyncSession {
        val connection = connectionRepository.findById(connectionId)
            ?: throw ConnectionNotFoundException()

        if (connection.userId != userId) {
            throw ConnectionNotFoundException()
        }

        if (connection.status != ConnectionStatus.CONNECTED) {
            throw ConnectionNotConnectedException()
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

    private fun encryptToken(token: String): String {
        return tokenEncryptor?.encrypt(token) ?: token
    }

    @Suppress("unused")
    private fun decryptToken(encrypted: String): String {
        return tokenEncryptor?.decrypt(encrypted) ?: encrypted
    }
}

class ConnectionNotFoundException : RuntimeException("Connection not found")
class ConnectionNotConnectedException : RuntimeException("Connection is not in connected state")
