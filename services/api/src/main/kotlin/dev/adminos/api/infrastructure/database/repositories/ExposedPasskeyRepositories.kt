package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.identity.Passkey
import dev.adminos.api.domain.identity.PasskeyRepository
import dev.adminos.api.domain.identity.RecoveryCodeRepository
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.RecoveryCodesTable
import dev.adminos.api.infrastructure.database.tables.WebAuthnCredentialsTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.time.Instant
import java.util.UUID

/** Passkeys on Postgres (V62, AOS-617). */
class ExposedPasskeyRepository : PasskeyRepository {

    override suspend fun save(passkey: Passkey): Passkey = dbQuery {
        WebAuthnCredentialsTable.insert {
            it[id] = passkey.id
            it[userId] = passkey.userId
            it[credentialId] = passkey.credentialId
            it[publicKeyCose] = passkey.publicKeyCose
            it[signatureCount] = passkey.signatureCount
            it[name] = passkey.name
            it[transports] = passkey.transports.sorted().joinToString(",")
            it[createdAt] = passkey.createdAt
            it[lastUsedAt] = passkey.lastUsedAt
        }
        passkey
    }

    override suspend fun findByUser(userId: UUID): List<Passkey> = dbQuery {
        WebAuthnCredentialsTable.selectAll().where { WebAuthnCredentialsTable.userId eq userId }
            .orderBy(WebAuthnCredentialsTable.createdAt).map { it.toPasskey() }
    }

    override suspend fun recordUse(id: UUID, signatureCount: Long, at: Instant): Unit = dbQuery {
        WebAuthnCredentialsTable.update({ WebAuthnCredentialsTable.id eq id }) {
            it[WebAuthnCredentialsTable.signatureCount] = signatureCount
            it[lastUsedAt] = at
        }
    }

    private fun ResultRow.toPasskey() = Passkey(
        id = this[WebAuthnCredentialsTable.id],
        userId = this[WebAuthnCredentialsTable.userId],
        credentialId = this[WebAuthnCredentialsTable.credentialId],
        publicKeyCose = this[WebAuthnCredentialsTable.publicKeyCose],
        signatureCount = this[WebAuthnCredentialsTable.signatureCount],
        name = this[WebAuthnCredentialsTable.name],
        transports = this[WebAuthnCredentialsTable.transports].split(',').filter { it.isNotBlank() }.toSet(),
        createdAt = this[WebAuthnCredentialsTable.createdAt],
        lastUsedAt = this[WebAuthnCredentialsTable.lastUsedAt]
    )
}

/** Recovery codes on Postgres (V62, AOS-617): hashes only; using one is a single conditional update. */
class ExposedRecoveryCodeRepository : RecoveryCodeRepository {

    override suspend fun replaceAll(userId: UUID, hashes: List<String>, at: Instant): Unit = dbQuery {
        RecoveryCodesTable.deleteWhere { RecoveryCodesTable.userId eq userId }
        hashes.forEach { h ->
            RecoveryCodesTable.insert {
                it[id] = UUID.randomUUID()
                it[RecoveryCodesTable.userId] = userId
                it[codeHash] = h
                it[createdAt] = at
            }
        }
    }

    override suspend fun consume(userId: UUID, hash: String, at: Instant): Boolean = dbQuery {
        RecoveryCodesTable.update({
            (RecoveryCodesTable.userId eq userId) and (RecoveryCodesTable.codeHash eq hash) and RecoveryCodesTable.usedAt.isNull()
        }) { it[usedAt] = at } == 1
    }

    override suspend fun countUnused(userId: UUID): Int = dbQuery {
        RecoveryCodesTable.selectAll().where { (RecoveryCodesTable.userId eq userId) and RecoveryCodesTable.usedAt.isNull() }.count().toInt()
    }
}
