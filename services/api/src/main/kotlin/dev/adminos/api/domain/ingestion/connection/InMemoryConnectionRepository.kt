package dev.adminos.api.domain.ingestion.connection

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemoryConnectionRepository : ConnectionRepository {
    private val connections = ConcurrentHashMap<UUID, UserConnection>()

    override suspend fun findById(id: UUID): UserConnection? = connections[id]

    override suspend fun findByUserId(userId: UUID): List<UserConnection> =
        connections.values.filter { it.userId == userId }

    override suspend fun findByUserAndSource(userId: UUID, sourceType: SourceType): UserConnection? =
        connections.values.find { it.userId == userId && it.sourceType == sourceType }

    override suspend fun findByGmailAddress(gmailAddress: String): UserConnection? =
        connections.values.find { it.gmailAddress == gmailAddress && it.sourceType == SourceType.GMAIL }

    override suspend fun findConnectedBySourceType(sourceType: SourceType): List<UserConnection> =
        connections.values.filter { it.sourceType == sourceType && it.status == ConnectionStatus.CONNECTED }

    override suspend fun save(connection: UserConnection): UserConnection {
        connections[connection.id] = connection
        return connection
    }

    override suspend fun update(connection: UserConnection): UserConnection {
        connections[connection.id] = connection.copy(updatedAt = Instant.now())
        return connections[connection.id]!!
    }
}
