package dev.adminos.api.infrastructure.database

import dev.adminos.api.domain.common.DuplicateEntityException
import dev.adminos.api.domain.identity.*
import dev.adminos.api.infrastructure.database.repositories.ExposedUserRepository
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Property 2: Repository Save/FindById Round-Trip (Exposed)
 *
 * Uses Testcontainers PostgreSQL with real migrations to verify
 * ExposedUserRepository behaves equivalently to InMemoryUserRepository.
 *
 * **Validates: Requirements 1.8, 8.5, 8.6**
 */
@Testcontainers
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedUserRepositoryIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("adminos_test")
            .withUsername("test")
            .withPassword("test")
    }

    @BeforeAll
    fun setup() {
        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username,
            password = postgres.password
        )
        // Run migration SQL files directly (they don't follow Flyway naming convention)
        val sqlDir = File("../../infra/migrations")
        val sqlFiles = sqlDir.listFiles()?.filter { it.extension == "sql" }?.sortedBy { it.name } ?: emptyList()
        transaction {
            for (file in sqlFiles) {
                exec(file.readText())
            }
        }
    }

    @Test
    fun `save and findById round-trip`() = runBlocking {
        val repo = ExposedUserRepository()
        val user = User(
            id = UUID.randomUUID(),
            email = "roundtrip-${UUID.randomUUID()}@example.com",
            googleId = "google_${UUID.randomUUID()}",
            name = "Test User",
            country = "IN",
            timezone = "Asia/Kolkata",
            onboardingStatus = OnboardingStatus.STARTED,
            role = UserRole.OWNER,
            isActive = true,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        repo.save(user)
        val found = repo.findById(user.id)

        assertNotNull(found)
        assertEquals(user.id, found!!.id)
        assertEquals(user.email, found.email)
        assertEquals(user.googleId, found.googleId)
        assertEquals(user.name, found.name)
        assertEquals(user.country, found.country)
        assertEquals(user.timezone, found.timezone)
        assertEquals(user.onboardingStatus, found.onboardingStatus)
        assertEquals(user.role, found.role)
        assertEquals(user.isActive, found.isActive)
    }

    @Test
    fun `findByGoogleId returns saved user`() = runBlocking {
        val repo = ExposedUserRepository()
        val googleId = "google_find_${UUID.randomUUID()}"
        val user = User(
            email = "google-find-${UUID.randomUUID()}@example.com",
            googleId = googleId,
            name = "Google User"
        )
        repo.save(user)
        val found = repo.findByGoogleId(googleId)

        assertNotNull(found)
        assertEquals(user.id, found!!.id)
        assertEquals(googleId, found.googleId)
    }

    @Test
    fun `findByEmail returns saved user`() = runBlocking {
        val repo = ExposedUserRepository()
        val email = "email-find-${UUID.randomUUID()}@example.com"
        val user = User(
            email = email,
            googleId = "google_email_${UUID.randomUUID()}",
            name = "Email User"
        )
        repo.save(user)
        val found = repo.findByEmail(email)

        assertNotNull(found)
        assertEquals(user.id, found!!.id)
        assertEquals(email, found.email)
    }

    @Test
    fun `findById returns null for non-existent user`() = runBlocking {
        val repo = ExposedUserRepository()
        val found = repo.findById(UUID.randomUUID())
        assertNull(found)
    }

    @Test
    fun `duplicate email throws DuplicateEntityException`() = runBlocking {
        val repo = ExposedUserRepository()
        val email = "dup-${UUID.randomUUID()}@example.com"
        val user1 = User(email = email, googleId = "google_dup1_${UUID.randomUUID()}", name = "User 1")
        val user2 = User(email = email, googleId = "google_dup2_${UUID.randomUUID()}", name = "User 2")

        repo.save(user1)
        assertThrows<DuplicateEntityException> {
            runBlocking { repo.save(user2) }
        }
    }

    @Test
    fun `update modifies user fields`() = runBlocking {
        val repo = ExposedUserRepository()
        val user = User(
            email = "update-${UUID.randomUUID()}@example.com",
            googleId = "google_update_${UUID.randomUUID()}",
            name = "Original Name"
        )
        repo.save(user)

        val updated = repo.update(user.copy(name = "Updated Name", country = "US"))
        assertEquals("Updated Name", updated.name)
        assertEquals("US", updated.country)

        val found = repo.findById(user.id)
        assertEquals("Updated Name", found!!.name)
        assertEquals("US", found.country)
    }
}
