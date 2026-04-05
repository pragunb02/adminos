package dev.adminos.api.config

data class AppConfig(
    val database: DatabaseConfig,
    val redis: RedisConfig,
    val auth: AuthConfig,
    val app: AppSettings,
    val security: SecurityConfig,
    val r2: R2Config,
    val google: GoogleConfig
) {
    companion object {
        fun load(): AppConfig = AppConfig(
            database = DatabaseConfig(
                url = env("DATABASE_URL", "jdbc:postgresql://localhost:5432/adminos"),
                user = env("DB_USER", "adminos"),
                password = env("DB_PASSWORD", "adminos"),
                maxPoolSize = env("DB_MAX_POOL_SIZE", "10").toInt()
            ),
            redis = RedisConfig(
                url = env("REDIS_URL", "redis://localhost:6379")
            ),
            auth = AuthConfig(
                jwtSecret = env("JWT_SECRET", "CHANGE-ME-generate-with-openssl-rand-base64-32"),
                jwtIssuer = env("JWT_ISSUER", "adminos"),
                accessTokenTtlSeconds = env("ACCESS_TOKEN_TTL", "3600").toLong(),
                refreshTokenTtlDays = env("REFRESH_TOKEN_TTL_DAYS", "30").toLong(),
                googleClientId = env("GOOGLE_CLIENT_ID", ""),
                googleClientSecret = env("GOOGLE_CLIENT_SECRET", ""),
                googlePubsubAudience = env("GOOGLE_PUBSUB_AUDIENCE", "")
            ),
            app = AppSettings(
                port = env("PORT", "8080").toInt(),
                appUrl = env("APP_URL", "http://localhost:3000"),
                apiUrl = env("API_URL", "http://localhost:8080")
            ),
            security = SecurityConfig(
                tokenEncryptionKey = env("TOKEN_ENCRYPTION_KEY", "CHANGE-ME-generate-with-openssl-rand-base64-32")
            ),
            r2 = R2Config(
                accessKey = env("R2_ACCESS_KEY", ""),
                secretKey = env("R2_SECRET_KEY", ""),
                bucket = env("R2_BUCKET", "adminos-uploads"),
                endpoint = env("R2_ENDPOINT", "")
            ),
            google = GoogleConfig(
                gmailPubsubTopic = env("GOOGLE_GMAIL_PUBSUB_TOPIC", ""),
                gmailPubsubSubscription = env("GOOGLE_GMAIL_PUBSUB_SUBSCRIPTION", "")
            )
        )

        private fun env(key: String, default: String): String =
            System.getenv(key) ?: default
    }
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
    val appUrl: String,
    val apiUrl: String
)

data class SecurityConfig(
    val tokenEncryptionKey: String
)

data class R2Config(
    val accessKey: String,
    val secretKey: String,
    val bucket: String,
    val endpoint: String
)

data class GoogleConfig(
    val gmailPubsubTopic: String,
    val gmailPubsubSubscription: String
)
