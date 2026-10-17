package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.ingestion.connection.OAuthPurpose
import dev.adminos.api.domain.ingestion.connection.OAuthState
import dev.adminos.api.domain.ingestion.connection.OAuthStateRepository
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.OAuthStatesTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.time.Instant
import java.util.UUID

class ExposedOAuthStateRepository : OAuthStateRepository {

    override suspend fun save(state: OAuthState): OAuthState = dbQuery {
        OAuthStatesTable.insert {
            it[id] = state.id
            it[userId] = state.userId
            it[purpose] = state.purpose.name.lowercase()
            it[stateHash] = state.stateHash
            it[redirectUri] = state.redirectUri
            it[codeVerifier] = state.codeVerifier
            it[createdAt] = state.createdAt
            it[expiresAt] = state.expiresAt
            it[usedAt] = state.usedAt
        }
        state
    }

    override suspend fun findByHash(stateHash: String): OAuthState? = dbQuery {
        OAuthStatesTable.selectAll().where { OAuthStatesTable.stateHash eq stateHash }.singleOrNull()?.toState()
    }

    /** One conditional UPDATE, so two concurrent redemptions can't both succeed. */
    override suspend fun markUsed(id: UUID, at: Instant): Boolean = dbQuery {
        OAuthStatesTable.update({ (OAuthStatesTable.id eq id) and OAuthStatesTable.usedAt.isNull() }) {
            it[usedAt] = at
        } == 1
    }

    override suspend fun deleteExpiredBefore(before: Instant): Int = dbQuery {
        OAuthStatesTable.deleteWhere { OAuthStatesTable.expiresAt less before }
    }

    private fun ResultRow.toState() = OAuthState(
        id = this[OAuthStatesTable.id],
        userId = this[OAuthStatesTable.userId],
        purpose = OAuthPurpose.valueOf(this[OAuthStatesTable.purpose].uppercase()),
        stateHash = this[OAuthStatesTable.stateHash],
        redirectUri = this[OAuthStatesTable.redirectUri],
        codeVerifier = this[OAuthStatesTable.codeVerifier],
        createdAt = this[OAuthStatesTable.createdAt],
        expiresAt = this[OAuthStatesTable.expiresAt],
        usedAt = this[OAuthStatesTable.usedAt]
    )
}
