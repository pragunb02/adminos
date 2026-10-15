package dev.adminos.api.infrastructure.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.adminos.api.config.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory

/**
 * Initializes the database connection pool and runs Flyway migrations.
 * Called once at application startup.
 */
object DatabaseFactory {

    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

    fun init(config: DatabaseConfig) {
        val dataSource = createHikariDataSource(config)

        // Run Flyway migrations
        runMigrations(dataSource)

        // Connect Exposed ORM
        Database.connect(dataSource)

        logger.info("Database initialized: {}", config.url)
    }

    /**
     * Builds the connection pool used by the app (and by integration tests, so they exercise
     * the same settings).
     */
    internal fun createHikariDataSource(config: DatabaseConfig): HikariDataSource {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.url
            username = config.user
            password = config.password
            maximumPoolSize = config.maxPoolSize
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            // Exposed maps Postgres ENUM/JSONB/INET columns as varchar/text; send strings untyped
            // so Postgres casts them to the column type (D-13).
            addDataSourceProperty("stringtype", "unspecified")
            validate()
        }
        return HikariDataSource(hikariConfig)
    }

    private fun runMigrations(dataSource: HikariDataSource) {
        try {
            val flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration", "filesystem:infra/migrations")
                .baselineOnMigrate(true)
                .load()

            val result = flyway.migrate()
            logger.info("Flyway migrations applied: {} migrations", result.migrationsExecuted)
        } catch (e: Exception) {
            logger.warn("Flyway migration skipped (DB may not be available): {}", e.message)
        }
    }
}
