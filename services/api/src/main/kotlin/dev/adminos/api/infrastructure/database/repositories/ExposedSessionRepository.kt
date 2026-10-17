package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.identity.Session
import dev.adminos.api.domain.identity.SessionRepository
import dev.adminos.api.domain.identity.RotatedToken
import dev.adminos.api.infrastructure.database.tables.SessionRotatedTokensTable
import dev.adminos.api.infrastructure.database.tables.SessionsTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import dev.adminos.api.infrastructure.database.dbQuery
import java.time.Instant
import java.util.UUID

class ExposedSessionRepository : SessionRepository {

    override suspend fun findByTokenHash(tokenHash: String): Session? = dbQuery {
        SessionsTable.selectAll().where { SessionsTable.tokenHash eq tokenHash }
            .singleOrNull()?.toSession()
    }

    override suspend fun findById(id: UUID): Session? = dbQuery {
        SessionsTable.selectAll().where { SessionsTable.id eq id }.singleOrNull()?.toSession()
    }

    override suspend fun findRecentByUserId(userId: UUID, limit: Int): List<Session> = dbQuery {
        SessionsTable.selectAll().where { SessionsTable.userId eq userId }
            .orderBy(SessionsTable.createdAt, SortOrder.DESC).limit(limit).map { it.toSession() }
    }

    override suspend fun revokeAllForUser(userId: UUID): Int = dbQuery {
        SessionsTable.update({ (SessionsTable.userId eq userId) and SessionsTable.revokedAt.isNull() and (SessionsTable.expiresAt greater Instant.now()) }) {
            it[revokedAt] = Instant.now()
        }
    }

    /** One transaction: a conditional UPDATE (only the current token rotates, so a race has one winner) and the history row. */
    override suspend fun rotate(sessionId: UUID, oldHash: String, newHash: String, at: Instant): Boolean = dbQuery {
        val won = SessionsTable.update({ (SessionsTable.id eq sessionId) and (SessionsTable.tokenHash eq oldHash) }) {
            it[tokenHash] = newHash
        } == 1
        if (won) {
            SessionRotatedTokensTable.insert {
                it[tokenHash] = oldHash
                it[SessionRotatedTokensTable.sessionId] = sessionId
                it[rotatedAt] = at
            }
        }
        won
    }

    override suspend fun findRotated(tokenHash: String): RotatedToken? = dbQuery {
        SessionRotatedTokensTable.selectAll().where { SessionRotatedTokensTable.tokenHash eq tokenHash }.singleOrNull()?.let {
            RotatedToken(it[SessionRotatedTokensTable.sessionId], it[SessionRotatedTokensTable.rotatedAt])
        }
    }

    override suspend fun findActiveByUserId(userId: UUID): List<Session> = dbQuery {
        SessionsTable.selectAll().where {
            (SessionsTable.userId eq userId) and
                SessionsTable.revokedAt.isNull() and
                (SessionsTable.expiresAt greater Instant.now())
        }.map { it.toSession() }
    }

    override suspend fun save(session: Session): Session = dbQuery {
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

    override suspend fun revoke(sessionId: UUID): Unit = dbQuery {
        SessionsTable.update({ SessionsTable.id eq sessionId }) {
            it[revokedAt] = Instant.now()
        }
    }

    override suspend fun updateLastActive(sessionId: UUID): Unit = dbQuery {
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
