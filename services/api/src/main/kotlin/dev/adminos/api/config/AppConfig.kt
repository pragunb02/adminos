package dev.adminos.api.config

data class AppConfig(
    val database: DatabaseConfig,
    val redis: RedisConfig,
    val auth: AuthConfig,
    val app: AppSettings,
    val security: SecurityConfig,
    val statementParser: StatementParserConfig,
    val google: GoogleConfig,
    val scheduler: SchedulerConfig = SchedulerConfig(),
    /** Email notifications (AOS-504); null = email is off. */
    val smtp: SmtpConfig? = null
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
                googlePubsubAudience = env("GOOGLE_PUBSUB_AUDIENCE", ""),
                // The service account the Pub/Sub push subscription signs as (AOS-609). Gmail push stays off until set.
                googlePubsubServiceAccount = env("GOOGLE_PUBSUB_SERVICE_ACCOUNT", "")
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
            ),
            scheduler = SchedulerConfig.from(System::getenv),
            smtp = SmtpConfig.from(System::getenv)
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
    val googlePubsubAudience: String,
    val googlePubsubServiceAccount: String = ""
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

/**
 * Scheduled jobs (AOS-501). On by default; `SCHEDULER_ENABLED=false` turns them off (e.g. a second
 * instance that should only serve requests, or tests). Invalid values stop start-up.
 */
data class SchedulerConfig(
    val enabled: Boolean = true,
    /** How often the scheduler checks for due slots. */
    val tick: java.time.Duration = java.time.Duration.ofSeconds(30),
    /** The time zone daily jobs are scheduled in (the owner's; per-user zones are a later step). */
    val zone: java.time.ZoneId = java.time.ZoneId.of("Asia/Kolkata")
) {
    init {
        require(tick >= java.time.Duration.ofSeconds(1)) { "SCHEDULER_TICK_SECONDS must be at least 1" }
    }

    companion object {
        internal fun from(getenv: (String) -> String?): SchedulerConfig {
            val enabled = when (val raw = getenv("SCHEDULER_ENABLED")?.trim()?.lowercase()) {
                null, "", "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("SCHEDULER_ENABLED must be true or false, was '$raw'")
            }
            val tick = getenv("SCHEDULER_TICK_SECONDS")?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
                val seconds = raw.toLongOrNull() ?: throw IllegalArgumentException("SCHEDULER_TICK_SECONDS must be a whole number, was '$raw'")
                java.time.Duration.ofSeconds(seconds)
            } ?: java.time.Duration.ofSeconds(30)
            val zone = getenv("SCHEDULER_ZONE")?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
                try {
                    java.time.ZoneId.of(raw)
                } catch (e: java.time.DateTimeException) {
                    throw IllegalArgumentException("SCHEDULER_ZONE must be a time zone such as Asia/Kolkata, was '$raw'", e)
                }
            } ?: java.time.ZoneId.of("Asia/Kolkata")
            return SchedulerConfig(enabled, tick, zone)
        }
    }
}

enum class SmtpSecurity { STARTTLS, TLS, NONE }

/**
 * SMTP server for email notifications (AOS-504). Email is off unless `SMTP_HOST` is set; once it
 * is, the rest must be valid or start-up stops — never a half-working mailer.
 */
data class SmtpConfig(
    val host: String,
    val port: Int,
    val security: SmtpSecurity,
    val username: String?,
    val password: String?,
    val from: jakarta.mail.internet.InternetAddress,
    val timeout: java.time.Duration = java.time.Duration.ofSeconds(10)
) {
    init {
        require(host.isNotBlank()) { "SMTP_HOST is blank" }
        require(port in 1..65535) { "SMTP_PORT must be 1–65535, was $port" }
        require((username == null) == (password == null)) { "Set both SMTP_USERNAME and SMTP_PASSWORD, or neither" }
        require(!(security == SmtpSecurity.NONE && username != null)) {
            "SMTP_SECURITY=none would send the SMTP password unencrypted; use starttls or tls"
        }
    }

    /** Never print the password. */
    override fun toString() = "SmtpConfig(host=$host, port=$port, security=$security, username=${username ?: "-"}, from=${from.address})"

    companion object {
        internal fun from(getenv: (String) -> String?): SmtpConfig? {
            fun value(key: String) = getenv(key)?.trim()?.takeIf { it.isNotEmpty() }
            val host = value("SMTP_HOST") ?: return null
            val security = value("SMTP_SECURITY")?.let { raw ->
                SmtpSecurity.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                    ?: throw IllegalArgumentException("SMTP_SECURITY must be starttls, tls or none, was '$raw'")
            } ?: SmtpSecurity.STARTTLS
            val port = value("SMTP_PORT")?.let { raw ->
                raw.toIntOrNull() ?: throw IllegalArgumentException("SMTP_PORT must be a number, was '$raw'")
            } ?: if (security == SmtpSecurity.TLS) 465 else 587
            val from = value("SMTP_FROM") ?: throw IllegalArgumentException("SMTP_FROM is required when SMTP_HOST is set")
            val address = try {
                jakarta.mail.internet.InternetAddress(from, true)
            } catch (e: jakarta.mail.internet.AddressException) {
                throw IllegalArgumentException("SMTP_FROM is not a valid address: '$from'", e)
            }
            return SmtpConfig(host, port, security, value("SMTP_USERNAME"), value("SMTP_PASSWORD"), address)
        }
    }
}
