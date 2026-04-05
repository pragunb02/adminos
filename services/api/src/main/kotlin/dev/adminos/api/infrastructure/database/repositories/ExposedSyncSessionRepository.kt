package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.ingestion.sync.*
import dev.adminos.api.infrastructure.database.tables.SyncSessionsTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.util.UUID

class ExposedSyncSessionRepository : SyncSessionRepository {

    override suspend fun findById(id: UUID): SyncSession? = newSuspendedTransaction {
        SyncSessionsTable.select { SyncSessionsTable.id eq id }
            .singleOrNull()?.toSyncSession()
    }

    override suspend fun findByUserId(userId: UUID, limit: Int): List<SyncSession> = newSuspendedTransaction {
        SyncSessionsTable.select { SyncSessionsTable.userId eq userId }
            .orderBy(SyncSessionsTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toSyncSession() }
    }

    override suspend fun save(session: SyncSession): SyncSession = newSuspendedTransaction {
        SyncSessionsTable.insert {
            it[id] = session.id
            it[userId] = session.userId
            it[connectionId] = session.connectionId
            it[syncType] = session.syncType.name.lowercase()
            it[status] = session.status.name.lowercase()
            it[totalItems] = session.totalItems
            it[processedItems] = session.processedItems
            it[failedItems] = session.failedItems
            it[duplicateItems] = session.duplicateItems
            it[netNewItems] = session.netNewItems
            it[startedAt] = session.startedAt
            it[completedAt] = session.completedAt
            it[errorDetails] = session.errorDetails
            it[createdAt] = session.createdAt
            it[updatedAt] = session.updatedAt
        }
        session
    }

    override suspend fun update(session: SyncSession): SyncSession = newSuspendedTransaction {
        val now = Instant.now()
        SyncSessionsTable.update({ SyncSessionsTable.id eq session.id }) {
            it[status] = session.status.name.lowercase()
            it[totalItems] = session.totalItems
            it[processedItems] = session.processedItems
            it[failedItems] = session.failedItems
            it[duplicateItems] = session.duplicateItems
            it[netNewItems] = session.netNewItems
            it[startedAt] = session.startedAt
            it[completedAt] = session.completedAt
            it[errorDetails] = session.errorDetails
            it[updatedAt] = now
        }
        session.copy(updatedAt = now)
    }

    private fun ResultRow.toSyncSession() = SyncSession(
        id = this[SyncSessionsTable.id],
        userId = this[SyncSessionsTable.userId],
        connectionId = this[SyncSessionsTable.connectionId],
        syncType = SyncType.valueOf(this[SyncSessionsTable.syncType].uppercase()),
        status = SyncSessionStatus.valueOf(this[SyncSessionsTable.status].uppercase()),
        totalItems = this[SyncSessionsTable.totalItems],
        processedItems = this[SyncSessionsTable.processedItems],
        failedItems = this[SyncSessionsTable.failedItems],
        duplicateItems = this[SyncSessionsTable.duplicateItems],
        netNewItems = this[SyncSessionsTable.netNewItems],
        startedAt = this[SyncSessionsTable.startedAt],
        completedAt = this[SyncSessionsTable.completedAt],
        errorDetails = this[SyncSessionsTable.errorDetails],
        createdAt = this[SyncSessionsTable.createdAt],
        updatedAt = this[SyncSessionsTable.updatedAt]
    )
}
