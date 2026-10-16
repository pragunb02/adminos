package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.identity.DeviceToken
import dev.adminos.api.domain.identity.DeviceTokenRepository
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.DeviceTokensTable as T
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.time.Instant
import java.util.UUID

class ExposedDeviceTokenRepository : DeviceTokenRepository {

    override suspend fun save(token: DeviceToken): DeviceToken = dbQuery {
        T.insert {
            it[id] = token.id; it[userId] = token.userId; it[name] = token.name
            it[scopes] = token.scopes.sorted().joinToString(" ")
            it[createdAt] = token.createdAt; it[expiresAt] = token.expiresAt
            it[lastUsedAt] = token.lastUsedAt; it[revokedAt] = token.revokedAt
        }
        token
    }

    override suspend fun findById(id: UUID): DeviceToken? = dbQuery {
        T.selectAll().where { T.id eq id }.singleOrNull()?.toDeviceToken()
    }

    override suspend fun findByUser(userId: UUID): List<DeviceToken> = dbQuery {
        T.selectAll().where { T.userId eq userId }.orderBy(T.createdAt, SortOrder.DESC).map { it.toDeviceToken() }
    }

    override suspend fun revoke(id: UUID, at: Instant): Unit = dbQuery {
        T.update({ (T.id eq id) and T.revokedAt.isNull() }) { it[revokedAt] = at }
    }

    override suspend fun touch(id: UUID, at: Instant): Unit = dbQuery {
        T.update({ T.id eq id }) { it[lastUsedAt] = at }
    }

    private fun ResultRow.toDeviceToken() = DeviceToken(
        id = this[T.id], userId = this[T.userId], name = this[T.name],
        scopes = this[T.scopes].split(' ').filter { it.isNotBlank() }.toSet(),
        createdAt = this[T.createdAt], expiresAt = this[T.expiresAt],
        lastUsedAt = this[T.lastUsedAt], revokedAt = this[T.revokedAt]
    )
}
