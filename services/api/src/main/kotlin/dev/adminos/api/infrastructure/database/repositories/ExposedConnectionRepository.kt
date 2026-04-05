package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.ingestion.connection.*
import dev.adminos.api.infrastructure.database.tables.UserConnectionsTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.util.UUID

class ExposedConnectionRepository : ConnectionRepository {

    override suspend fun findById(id: UUID): UserConnection? = newSuspendedTransaction {
        UserConnectionsTable.select { UserConnectionsTable.id eq id }
            .singleOrNull()?.toConnection()
    }

    override suspend fun findByUserId(userId: UUID): List<UserConnection> = newSuspendedTransaction {
        UserConnectionsTable.select { UserConnectionsTable.userId eq userId }
            .map { it.toConnection() }
    }

    override suspend fun findByUserAndSource(userId: UUID, sourceType: SourceType): UserConnection? =
        newSuspendedTransaction {
            UserConnectionsTable.select {
                (UserConnectionsTable.userId eq userId) and
                    (UserConnectionsTable.sourceType eq sourceType.name.lowercase())
            }.singleOrNull()?.toConnection()
        }

    override suspend fun findByGmailAddress(gmailAddress: String): UserConnection? = newSuspendedTransaction {
        UserConnectionsTable.select {
            (UserConnectionsTable.gmailAddress eq gmailAddress) and
                (UserConnectionsTable.sourceType eq SourceType.GMAIL.name.lowercase())
        }.singleOrNull()?.toConnection()
    }

    override suspend fun findConnectedBySourceType(sourceType: SourceType): List<UserConnection> =
        newSuspendedTransaction {
            UserConnectionsTable.select {
                (UserConnectionsTable.sourceType eq sourceType.name.lowercase()) and
                    (UserConnectionsTable.status eq ConnectionStatus.CONNECTED.name.lowercase())
            }.map { it.toConnection() }
        }

    override suspend fun save(connection: UserConnection): UserConnection = newSuspendedTransaction {
        UserConnectionsTable.insert {
            it[id] = connection.id
            it[userId] = connection.userId
            it[sourceType] = connection.sourceType.name.lowercase()
            it[status] = connection.status.name.lowercase()
            it[accessToken] = connection.accessToken
            it[refreshToken] = connection.refreshToken
            it[tokenExpiresAt] = connection.tokenExpiresAt
            it[oauthScope] = connection.oauthScope?.joinToString(",")
            it[gmailAddress] = connection.gmailAddress
            it[pubsubExpiry] = connection.pubsubExpiry
            it[historyId] = connection.historyId
            it[lastSyncedAt] = connection.lastSyncedAt
            it[lastSyncStatus] = connection.lastSyncStatus
            it[lastError] = connection.lastError
            it[nextSyncAt] = connection.nextSyncAt
            it[totalSynced] = connection.totalSynced
            it[createdAt] = connection.createdAt
            it[updatedAt] = connection.updatedAt
        }
        connection
    }

    override suspend fun update(connection: UserConnection): UserConnection = newSuspendedTransaction {
        val now = Instant.now()
        UserConnectionsTable.update({ UserConnectionsTable.id eq connection.id }) {
            it[status] = connection.status.name.lowercase()
            it[accessToken] = connection.accessToken
            it[refreshToken] = connection.refreshToken
            it[tokenExpiresAt] = connection.tokenExpiresAt
            it[oauthScope] = connection.oauthScope?.joinToString(",")
            it[gmailAddress] = connection.gmailAddress
            it[pubsubExpiry] = connection.pubsubExpiry
            it[historyId] = connection.historyId
            it[lastSyncedAt] = connection.lastSyncedAt
            it[lastSyncStatus] = connection.lastSyncStatus
            it[lastError] = connection.lastError
            it[nextSyncAt] = connection.nextSyncAt
            it[totalSynced] = connection.totalSynced
            it[updatedAt] = now
        }
        connection.copy(updatedAt = now)
    }

    private fun ResultRow.toConnection() = UserConnection(
        id = this[UserConnectionsTable.id],
        userId = this[UserConnectionsTable.userId],
        sourceType = SourceType.valueOf(this[UserConnectionsTable.sourceType].uppercase()),
        status = ConnectionStatus.valueOf(this[UserConnectionsTable.status].uppercase()),
        accessToken = this[UserConnectionsTable.accessToken],
        refreshToken = this[UserConnectionsTable.refreshToken],
        tokenExpiresAt = this[UserConnectionsTable.tokenExpiresAt],
        oauthScope = this[UserConnectionsTable.oauthScope]?.split(",")?.filter { it.isNotBlank() },
        gmailAddress = this[UserConnectionsTable.gmailAddress],
        pubsubExpiry = this[UserConnectionsTable.pubsubExpiry],
        historyId = this[UserConnectionsTable.historyId],
        lastSyncedAt = this[UserConnectionsTable.lastSyncedAt],
        lastSyncStatus = this[UserConnectionsTable.lastSyncStatus],
        lastError = this[UserConnectionsTable.lastError],
        nextSyncAt = this[UserConnectionsTable.nextSyncAt],
        totalSynced = this[UserConnectionsTable.totalSynced],
        createdAt = this[UserConnectionsTable.createdAt],
        updatedAt = this[UserConnectionsTable.updatedAt]
    )
}
