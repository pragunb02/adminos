package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.*
import dev.adminos.api.infrastructure.database.tables.AccountsTable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import dev.adminos.api.infrastructure.database.dbQuery
import java.time.Instant
import java.util.UUID

class ExposedAccountRepository : AccountRepository {

    override suspend fun save(account: Account): Account = dbQuery {
        AccountsTable.insert {
            it[id] = account.id
            it[userId] = account.userId
            it[accountType] = account.accountType.name.lowercase()
            it[bankName] = account.bankName
            it[bankCode] = account.bankCode
            it[accountLast4] = account.accountLast4
            it[accountName] = account.accountName
            it[isPrimary] = account.isPrimary
            it[currency] = account.currency
            it[connectionId] = account.connectionId
            it[metadata] = account.metadata?.let { m -> Json.encodeToString(kotlinx.serialization.serializer(), m) }
            it[createdAt] = account.createdAt
            it[updatedAt] = account.updatedAt
        }
        account
    }

    override suspend fun findById(id: UUID): Account? = dbQuery {
        AccountsTable.selectAll().where { AccountsTable.id eq id }.singleOrNull()?.toAccount()
    }

    override suspend fun findByUserId(userId: UUID): List<Account> = dbQuery {
        AccountsTable.selectAll().where { AccountsTable.userId eq userId }
            .orderBy(AccountsTable.createdAt, SortOrder.DESC)
            .map { it.toAccount() }
    }

    override suspend fun findByUserAndBankAndLast4(userId: UUID, bankCode: String, accountLast4: String): Account? =
        dbQuery {
            AccountsTable.selectAll().where {
                (AccountsTable.userId eq userId) and
                    (AccountsTable.bankCode eq bankCode) and
                    (AccountsTable.accountLast4 eq accountLast4)
            }.singleOrNull()?.toAccount()
        }

    override suspend fun update(account: Account): Account = dbQuery {
        val now = Instant.now()
        AccountsTable.update({ AccountsTable.id eq account.id }) {
            it[accountType] = account.accountType.name.lowercase()
            it[bankName] = account.bankName
            it[bankCode] = account.bankCode
            it[accountName] = account.accountName
            it[isPrimary] = account.isPrimary
            it[updatedAt] = now
        }
        account.copy(updatedAt = now)
    }

    @Suppress("UNCHECKED_CAST")
    private fun ResultRow.toAccount() = Account(
        id = this[AccountsTable.id],
        userId = this[AccountsTable.userId],
        accountType = AccountType.valueOf(this[AccountsTable.accountType].uppercase()),
        bankName = this[AccountsTable.bankName],
        bankCode = this[AccountsTable.bankCode],
        accountLast4 = this[AccountsTable.accountLast4],
        accountName = this[AccountsTable.accountName],
        isPrimary = this[AccountsTable.isPrimary],
        currency = this[AccountsTable.currency],
        connectionId = this[AccountsTable.connectionId],
        metadata = this[AccountsTable.metadata]?.let {
            Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(it) as? Map<String, Any?>
        },
        createdAt = this[AccountsTable.createdAt],
        updatedAt = this[AccountsTable.updatedAt]
    )
}
