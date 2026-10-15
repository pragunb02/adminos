package dev.adminos.api.infrastructure.database

import dev.adminos.api.config.DatabaseConfig
import dev.adminos.api.domain.identity.OnboardingStatus
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.infrastructure.database.repositories.ExposedUserRepository
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * One Postgres container for the whole integration suite, migrated once with real Flyway
 * through the production pool builder. Tests isolate themselves by creating their own user.
 */
object PostgresTestDatabase {

    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("adminos_test")
            .withUsername("test")
            .withPassword("test")
            .also { it.start() }
    }

    /** Pool configured exactly like production (DatabaseFactory). */
    val dataSource: DataSource by lazy {
        DatabaseFactory.createHikariDataSource(
            DatabaseConfig(url = container.jdbcUrl, user = container.username, password = container.password, maxPoolSize = 4)
        ).also {
            DatabaseFactory.migrate(it)
            Database.connect(it)
        }
    }

    /** Ensures the database is up, migrated and connected to Exposed. */
    fun ensureStarted() {
        dataSource
    }

    /** Inserts a fresh user (most tables reference users) and returns its id. */
    suspend fun newUser(): UUID {
        ensureStarted()
        val id = UUID.randomUUID()
        ExposedUserRepository().save(
            User(
                id = id,
                email = "user-$id@example.com",
                googleId = "google-$id",
                name = "Test User",
                country = "IN",
                timezone = "Asia/Kolkata",
                onboardingStatus = OnboardingStatus.STARTED,
                role = UserRole.OWNER,
                isActive = true,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
        )
        return id
    }
}
