package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.ingestion.connection.*
import dev.adminos.api.infrastructure.database.tables.UserConnectionsTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.*
import dev.adminos.api.infrastructure.database.dbQuery
import java.time.Instant
import java.util.UUID

class ExposedConnectionRepository : ConnectionRepository {

    override suspend fun findById(id: UUID): UserConnection? = dbQuery {
        UserConnectionsTable.selectAll().where { UserConnectionsTable.id eq id }
            .singleOrNull()?.toConnection()
    }

    override suspend fun findByUserId(userId: UUID): List<UserConnection> = dbQuery {
        UserConnectionsTable.selectAll().where { UserConnectionsTable.userId eq userId }
            .map { it.toConnection() }
    }

    override suspend fun findByUserAndSource(userId: UUID, sourceType: SourceType): UserConnection? =
        dbQuery {
            UserConnectionsTable.selectAll().where {
                (UserConnectionsTable.userId eq userId) and
                    (UserConnectionsTable.sourceType eq sourceType.name.lowercase())
            }.singleOrNull()?.toConnection()
        }

    override suspend fun findByGmailAddress(gmailAddress: String): UserConnection? = dbQuery {
        UserConnectionsTable.selectAll().where {
            (UserConnectionsTable.gmailAddress eq gmailAddress) and
                (UserConnectionsTable.sourceType eq SourceType.GMAIL.name.lowercase())
        }.singleOrNull()?.toConnection()
    }

    override suspend fun findConnectedBySourceType(sourceType: SourceType): List<UserConnection> =
        dbQuery {
            UserConnectionsTable.selectAll().where {
                (UserConnectionsTable.sourceType eq sourceType.name.lowercase()) and
                    (UserConnectionsTable.status eq ConnectionStatus.CONNECTED.name.lowercase())
            }.map { it.toConnection() }
        }

    override suspend fun save(connection: UserConnection): UserConnection = dbQuery {
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
            writeWatch(it, connection.watch)
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

    override suspend fun update(connection: UserConnection): UserConnection = dbQuery {
        val now = Instant.now()
        UserConnectionsTable.update({ UserConnectionsTable.id eq connection.id }) {
            it[status] = connection.status.name.lowercase()
            it[accessToken] = connection.accessToken
            it[refreshToken] = connection.refreshToken
            it[tokenExpiresAt] = connection.tokenExpiresAt
            it[oauthScope] = connection.oauthScope?.joinToString(",")
            it[gmailAddress] = connection.gmailAddress
            writeWatch(it, connection.watch)
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

    override suspend fun updateWatch(id: UUID, watch: GmailWatch) = dbQuery {
        UserConnectionsTable.update({ UserConnectionsTable.id eq id }) {
            writeWatch(it, watch)
            it[updatedAt] = Instant.now()
        }
        Unit
    }

    override suspend fun updateAccessToken(id: UUID, accessToken: String, expiresAt: Instant?) = dbQuery {
        UserConnectionsTable.update({ UserConnectionsTable.id eq id }) {
            it[UserConnectionsTable.accessToken] = accessToken
            it[tokenExpiresAt] = expiresAt
            it[updatedAt] = Instant.now()
        }
        Unit
    }

    override suspend fun markError(id: UUID, reason: String) = dbQuery {
        UserConnectionsTable.update({ UserConnectionsTable.id eq id }) {
            it[status] = ConnectionStatus.ERROR.name.lowercase()
            it[lastError] = reason
            it[updatedAt] = Instant.now()
        }
        Unit
    }

    private fun writeWatch(row: UpdateBuilder<*>, watch: GmailWatch) {
        row[UserConnectionsTable.watchExpiresAt] = watch.expiresAt
        row[UserConnectionsTable.watchHistoryId] = watch.historyId
        row[UserConnectionsTable.watchError] = watch.error
        row[UserConnectionsTable.watchCheckedAt] = watch.checkedAt
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
        watch = GmailWatch(
            expiresAt = this[UserConnectionsTable.watchExpiresAt],
            historyId = this[UserConnectionsTable.watchHistoryId],
            error = this[UserConnectionsTable.watchError],
            checkedAt = this[UserConnectionsTable.watchCheckedAt]
        ),
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
