package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.identity.Session
import dev.adminos.api.domain.identity.SessionRepository
import dev.adminos.api.infrastructure.database.tables.SessionsTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.util.UUID

class ExposedSessionRepository : SessionRepository {

    override suspend fun findByTokenHash(tokenHash: String): Session? = newSuspendedTransaction {
        SessionsTable.select { SessionsTable.tokenHash eq tokenHash }
            .singleOrNull()?.toSession()
    }

    override suspend fun findActiveByUserId(userId: UUID): List<Session> = newSuspendedTransaction {
        SessionsTable.select {
            (SessionsTable.userId eq userId) and
                SessionsTable.revokedAt.isNull() and
                (SessionsTable.expiresAt greater Instant.now())
        }.map { it.toSession() }
    }

    override suspend fun save(session: Session): Session = newSuspendedTransaction {
        SessionsTable.insert {
            it[id] = session.id
            it[userId] = session.userId
            it[tokenHash] = session.tokenHash
            it[deviceId] = session.deviceId
            it[ipAddress] = session.ipAddress
            it[userAgent] = session.userAgent
            it[expiresAt] = session.expiresAt
            it[lastActiveAt] = session.lastActiveAt
            it[createdAt] = session.createdAt
            it[revokedAt] = session.revokedAt
        }
        session
    }

    override suspend fun revoke(sessionId: UUID): Unit = newSuspendedTransaction {
        SessionsTable.update({ SessionsTable.id eq sessionId }) {
            it[revokedAt] = Instant.now()
        }
    }

    override suspend fun updateLastActive(sessionId: UUID): Unit = newSuspendedTransaction {
        SessionsTable.update({ SessionsTable.id eq sessionId }) {
            it[lastActiveAt] = Instant.now()
        }
    }

    private fun ResultRow.toSession() = Session(
        id = this[SessionsTable.id],
        userId = this[SessionsTable.userId],
        tokenHash = this[SessionsTable.tokenHash],
        deviceId = this[SessionsTable.deviceId],
        ipAddress = this[SessionsTable.ipAddress],
        userAgent = this[SessionsTable.userAgent],
        expiresAt = this[SessionsTable.expiresAt],
        lastActiveAt = this[SessionsTable.lastActiveAt],
        createdAt = this[SessionsTable.createdAt],
        revokedAt = this[SessionsTable.revokedAt]
    )
}
