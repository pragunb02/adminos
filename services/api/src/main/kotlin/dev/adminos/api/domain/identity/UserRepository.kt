package dev.adminos.api.domain.identity

import java.util.UUID

interface UserRepository {
    suspend fun findById(id: UUID): User?
    suspend fun findByGoogleId(googleId: String): User?
    suspend fun findByEmail(email: String): User?
    suspend fun findByOnboardingIncomplete(): List<User>
    suspend fun save(user: User): User
    suspend fun update(user: User): User
}
