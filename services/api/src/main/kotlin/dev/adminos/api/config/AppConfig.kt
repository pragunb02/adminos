package dev.adminos.api.config

data class AppConfig(
    val database: DatabaseConfig,
    val redis: RedisConfig,
    val auth: AuthConfig,
    val app: AppSettings,
    val security: SecurityConfig,
    val statementParser: StatementParserConfig,
    val google: GoogleConfig
) {
    companion object {
        fun load(): AppConfig = AppConfig(
            database = DatabaseConfig(
                url = env("DATABASE_URL", "jdbc:postgresql://localhost:5432/adminos"),
                user = env("DB_USER", "adminos"),
                password = secret("DB_PASSWORD") { "" },
                maxPoolSize = env("DB_MAX_POOL_SIZE", "10").toInt()
            ),
            redis = RedisConfig(
                url = env("REDIS_URL", "redis://localhost:6379")
            ),
            auth = AuthConfig(
                jwtSecret = secret("JWT_SECRET") { DevSecrets.jwtSecret },
                jwtIssuer = env("JWT_ISSUER", "adminos"),
                accessTokenTtlSeconds = env("ACCESS_TOKEN_TTL", "3600").toLong(),
                refreshTokenTtlDays = env("REFRESH_TOKEN_TTL_DAYS", "30").toLong(),
                googleClientId = env("GOOGLE_CLIENT_ID", ""),
                googleClientSecret = env("GOOGLE_CLIENT_SECRET", ""),
                googlePubsubAudience = env("GOOGLE_PUBSUB_AUDIENCE", "")
            ),
            app = AppSettings(
                port = env("PORT", "8080").toInt(),
                // Loopback by default; the Docker image sets 0.0.0.0, where the 127.0.0.1-only publish is the boundary.
                bindHost = env("BIND_HOST", "127.0.0.1"),
                appUrl = env("APP_URL", "http://localhost:3000"),
                apiUrl = env("API_URL", "http://localhost:8080")
            ),
            security = SecurityConfig(
                tokenEncryptionKey = secret("TOKEN_ENCRYPTION_KEY") { DevSecrets.tokenEncryptionKey }
            ),
            statementParser = StatementParserConfig(
                // Internal service (D-37); compose sets http://pdf-parser:8000.
                url = env("STATEMENT_PARSER_URL", "http://127.0.0.1:8000")
            ),
            google = GoogleConfig(
                gmailPubsubTopic = env("GOOGLE_GMAIL_PUBSUB_TOPIC", ""),
                gmailPubsubSubscription = env("GOOGLE_GMAIL_PUBSUB_SUBSCRIPTION", "")
            )
        )

        private fun env(key: String, default: String): String =
            System.getenv(key) ?: default

        /**
         * A secret from the environment — never a default anyone can read in this public repo (AOS-608).
         * With a database (real data) a missing or placeholder secret stops start-up; in in-memory dev
         * mode a random per-process secret is used and a warning is logged.
         */
        internal fun secret(key: String, getenv: (String) -> String? = System::getenv, dev: () -> String): String {
            val value = getenv(key)?.trim()
            if (!value.isNullOrEmpty() && !isPlaceholder(value)) return value
            check(getenv("DATABASE_URL").isNullOrBlank()) {
                "$key is required when DATABASE_URL is set. Generate one with: openssl rand -base64 32"
            }
            org.slf4j.LoggerFactory.getLogger(AppConfig::class.java)
                .warn("{} not set: using a random secret for this process only (in-memory dev mode).", key)
            return dev()
        }

        private val PLACEHOLDER = Regex("(?i)^(change[-_]?me|generate[-_]|your[-_])")
        internal fun isPlaceholder(value: String) = PLACEHOLDER.containsMatchIn(value)
    }
}

/** Random secrets for in-memory dev mode, stable for the life of the process. */
internal object DevSecrets {
    private fun random(bytes: Int) = java.util.Base64.getEncoder()
        .encodeToString(ByteArray(bytes).also(java.security.SecureRandom()::nextBytes))
    val jwtSecret: String by lazy { random(48) }
    val tokenEncryptionKey: String by lazy { random(32) }
}

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int
)

data class RedisConfig(
    val url: String
)

data class AuthConfig(
    val jwtSecret: String,
    val jwtIssuer: String,
    val accessTokenTtlSeconds: Long,
    val refreshTokenTtlDays: Long,
    val googleClientId: String,
    val googleClientSecret: String,
    val googlePubsubAudience: String
)

data class AppSettings(
    val port: Int,
    val bindHost: String,
    val appUrl: String,
    val apiUrl: String
)

data class SecurityConfig(
    val tokenEncryptionKey: String
)

data class StatementParserConfig(
    val url: String
)

data class GoogleConfig(
    val gmailPubsubTopic: String,
    val gmailPubsubSubscription: String
)
