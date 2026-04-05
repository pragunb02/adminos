package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.common.DuplicateEntityException
import dev.adminos.api.domain.common.EntityNotFoundException
import dev.adminos.api.domain.common.RepositoryException
import dev.adminos.api.domain.identity.*
import dev.adminos.api.infrastructure.database.tables.UsersTable
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.util.UUID

class ExposedUserRepository : UserRepository {

    override suspend fun findById(id: UUID): User? = newSuspendedTransaction {
        UsersTable.select { UsersTable.id eq id }.singleOrNull()?.toUser()
    }

    override suspend fun findByGoogleId(googleId: String): User? = newSuspendedTransaction {
        UsersTable.select { UsersTable.googleId eq googleId }.singleOrNull()?.toUser()
    }

    override suspend fun findByEmail(email: String): User? = newSuspendedTransaction {
        UsersTable.select { UsersTable.email eq email }.singleOrNull()?.toUser()
    }

    override suspend fun findByOnboardingIncomplete(): List<User> = newSuspendedTransaction {
        UsersTable.select {
            (UsersTable.onboardingStatus neq OnboardingStatus.COMPLETED.name.lowercase()) and
                (UsersTable.isActive eq true) and
                UsersTable.deletedAt.isNull()
        }.map { it.toUser() }
    }

    override suspend fun save(user: User): User = newSuspendedTransaction {
        try {
            UsersTable.insert {
                it[id] = user.id
                it[email] = user.email
                it[name] = user.name
                it[avatarUrl] = user.avatarUrl
                it[googleId] = user.googleId
                it[phone] = user.phone
                it[country] = user.country
                it[timezone] = user.timezone
                it[onboardingStatus] = user.onboardingStatus.name.lowercase()
                it[role] = user.role.name.lowercase()
                it[isActive] = user.isActive
                it[createdAt] = user.createdAt
                it[updatedAt] = user.updatedAt
                it[deletedAt] = user.deletedAt
            }
            user
        } catch (e: ExposedSQLException) {
            when {
                e.message?.contains("uq_users_email") == true ->
                    throw DuplicateEntityException("User with email ${user.email} already exists")
                e.message?.contains("uq_users_google_id") == true ->
                    throw DuplicateEntityException("User with Google ID already exists")
                else -> throw RepositoryException("Failed to save user: ${e.message}", e)
            }
        }
    }

    override suspend fun update(user: User): User = newSuspendedTransaction {
        val now = Instant.now()
        UsersTable.update({ UsersTable.id eq user.id }) {
            it[email] = user.email
            it[name] = user.name
            it[avatarUrl] = user.avatarUrl
            it[phone] = user.phone
            it[country] = user.country
            it[timezone] = user.timezone
            it[onboardingStatus] = user.onboardingStatus.name.lowercase()
            it[role] = user.role.name.lowercase()
            it[isActive] = user.isActive
            it[updatedAt] = now
            it[deletedAt] = user.deletedAt
        }
        UsersTable.select { UsersTable.id eq user.id }
            .singleOrNull()?.toUser()
            ?: throw EntityNotFoundException("User ${user.id} not found")
    }

    private fun ResultRow.toUser() = User(
        id = this[UsersTable.id],
        email = this[UsersTable.email],
        name = this[UsersTable.name],
        avatarUrl = this[UsersTable.avatarUrl],
        googleId = this[UsersTable.googleId],
        phone = this[UsersTable.phone],
        country = this[UsersTable.country],
        timezone = this[UsersTable.timezone],
        onboardingStatus = OnboardingStatus.valueOf(this[UsersTable.onboardingStatus].uppercase()),
        role = UserRole.valueOf(this[UsersTable.role].uppercase()),
        isActive = this[UsersTable.isActive],
        createdAt = this[UsersTable.createdAt],
        updatedAt = this[UsersTable.updatedAt],
        deletedAt = this[UsersTable.deletedAt]
    )
}
