package dev.adminos.api.infrastructure.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.adminos.api.config.DatabaseConfig
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * Initializes the database connection pool and runs Flyway migrations.
 * Called once at application startup. Flyway is the only migration runner (D-12):
 * migrations live in `src/main/resources/db/migration` and ship inside the API.
 */
object DatabaseFactory {

    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

    private const val MIGRATIONS_LOCATION = "classpath:db/migration"

    /**
     * Single source of truth for "is a database configured?". Used by DI to choose
     * Exposed vs in-memory repositories, and by startup to decide whether to migrate.
     */
    fun isConfigured(): Boolean = !System.getenv("DATABASE_URL").isNullOrBlank()

    /** Creates the pool, applies pending migrations and connects Exposed. Fails fast on any error. */
    fun init(config: DatabaseConfig) {
        val dataSource = createHikariDataSource(config)
        migrate(dataSource)
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

    /**
     * Applies pending migrations. Throws if any migration fails, so the app never starts
     * against a half-migrated schema. No `baselineOnMigrate`: an unexpected non-empty schema
     * must fail loudly rather than be silently marked as current.
     *
     * @return number of migrations applied (0 when the schema is already current)
     */
    internal fun migrate(dataSource: DataSource): Int {
        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations(MIGRATIONS_LOCATION)
            .load()
            .migrate()
        logger.info(
            "Flyway: {} migration(s) applied, schema now at version {}",
            result.migrationsExecuted, result.targetSchemaVersion ?: "unchanged"
        )
        return result.migrationsExecuted
    }
}
