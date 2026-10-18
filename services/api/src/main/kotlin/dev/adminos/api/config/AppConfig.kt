package dev.adminos.api.config

data class AppConfig(
    val database: DatabaseConfig,
    val auth: AuthConfig,
    val app: AppSettings,
    val security: SecurityConfig,
    val statementParser: StatementParserConfig,
    val google: GoogleConfig,
    val scheduler: SchedulerConfig = SchedulerConfig(),
    /** Email notifications (AOS-504); null = email is off. */
    val smtp: SmtpConfig? = null,
    /** Web Push (AOS-510); null = push is off. */
    val vapid: VapidConfig? = null
) {
    companion object {
        fun load(): AppConfig = AppConfig(
            database = DatabaseConfig(
                url = env("DATABASE_URL", "jdbc:postgresql://localhost:5432/adminos"),
                user = env("DB_USER", "adminos"),
                password = secret("DB_PASSWORD") { "" },
                maxPoolSize = env("DB_MAX_POOL_SIZE", "10").toInt()
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
                googlePubsubServiceAccount = env("GOOGLE_PUBSUB_SERVICE_ACCOUNT", ""),
                ownerEmail = ownerEmail()
            ),
            app = AppSettings(
                port = env("PORT", "8080").toInt(),
                // Loopback by default; the Docker image sets 0.0.0.0, where the 127.0.0.1-only publish is the boundary.
                bindHost = env("BIND_HOST", "127.0.0.1"),
                appUrl = appUrl(),
                apiUrl = env("API_URL", "http://localhost:8080"),
                trustForwardedFor = trustForwardedFor(System::getenv)
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
            smtp = SmtpConfig.from(System::getenv),
            vapid = VapidConfig.from(System::getenv)
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

        /**
         * `OWNER_EMAIL`, the owner's Google email (AOS-614). With a database it is required and must look
         * like an email, so a real deployment can never start open to everyone. In in-memory dev mode it may
         * be blank, and then nobody can sign in (logged).
         */
        internal fun ownerEmail(getenv: (String) -> String? = System::getenv): String {
            val value = getenv("OWNER_EMAIL")?.trim().orEmpty()
            if (value.isNotEmpty()) {
                require(dev.adminos.api.domain.identity.OwnerPolicy.isValid(value)) { "OWNER_EMAIL must be an email address" }
                return value
            }
            check(getenv("DATABASE_URL").isNullOrBlank()) { "OWNER_EMAIL is required when DATABASE_URL is set: the Google email allowed to sign in" }
            org.slf4j.LoggerFactory.getLogger(AppConfig::class.java)
                .warn("OWNER_EMAIL not set: nobody can sign in with Google (in-memory dev mode).")
            return ""
        }

        /**
         * `APP_URL`, the one origin the browser uses (D-32), e.g. `https://adminos.<tailnet>.ts.net` (AOS-616).
         * Fail closed: it must be a bare origin (no path, query, fragment or credentials), and plain `http` is
         * allowed only for a loopback host. Anything else stops start-up, so cookies are never sent without
         * `Secure` and the OAuth redirect never points at a cleartext or malformed address.
         */
        internal fun appUrl(getenv: (String) -> String? = System::getenv): String {
            val raw = getenv("APP_URL")?.trim()?.removeSuffix("/").orEmpty()
            if (raw.isEmpty()) return "http://localhost:3000"
            val uri = runCatching { java.net.URI(raw) }.getOrNull()
            require(
                uri != null && uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() &&
                    uri.rawUserInfo == null && uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null
            ) { "APP_URL must be an origin like https://adminos.example.ts.net (no path, query or credentials)" }
            require(uri.scheme.equals("https", ignoreCase = true) || dev.adminos.api.domain.identity.isLocalDevUrl(raw)) {
                "APP_URL must use https unless the host is localhost, 127.0.0.1 or ::1 (got ${uri.scheme}://${uri.host})"
            }
            return raw
        }

        /**
         * `TRUST_FORWARDED_FOR` (AOS-618, AUD-03): off by default. The web proxy forwards a client-sent
         * `X-Forwarded-For` unchanged, so trusting it lets anyone pick their IP. Turn it on only behind an edge proxy
         * that *appends* the real peer (Tailscale serve, Caddy, Cloudflare Tunnel; AOS-616). Anything but true/false stops start-up.
         */
        internal fun trustForwardedFor(getenv: (String) -> String?): Boolean =
            when (val raw = getenv("TRUST_FORWARDED_FOR")?.trim()?.lowercase()) {
                null, "", "false" -> false
                "true" -> true
                else -> throw IllegalArgumentException("TRUST_FORWARDED_FOR must be true or false, was '$raw'")
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

data class AuthConfig(
    val jwtSecret: String,
    val jwtIssuer: String,
    val accessTokenTtlSeconds: Long,
    val refreshTokenTtlDays: Long,
    val googleClientId: String,
    val googleClientSecret: String,
    val googlePubsubAudience: String,
    val googlePubsubServiceAccount: String = "",
    /** The only Google account that may sign in (AOS-614, D-91). Blank = nobody can. */
    val ownerEmail: String = ""
)

data class AppSettings(
    val port: Int,
    val bindHost: String,
    val appUrl: String,
    val apiUrl: String,
    /** Use the last `X-Forwarded-For` hop as the client IP (only behind an edge that appends it; AOS-618). */
    val trustForwardedFor: Boolean = false
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

/**
 * Web Push signing keys (AOS-510, RFC 8292). Generate with `./gradlew -q vapidKeys`. Push is off
 * unless both keys are set; a half or mismatched pair, or a missing contact, stops start-up.
 */
data class VapidConfig(
    /** 65-byte uncompressed P-256 point, URL-safe base64 — given to browsers when they subscribe. */
    val publicKey: String,
    /** 32-byte P-256 scalar, URL-safe base64. Secret. */
    val privateKey: String,
    /** `mailto:` or `https:` contact the push services can reach if something goes wrong. */
    val subject: String
) {
    init {
        require(subject.startsWith("mailto:") || subject.startsWith("https://")) { "VAPID_SUBJECT must be a mailto: or https: contact" }
        val private = try {
            dev.adminos.api.infrastructure.push.P256.privateKey(dev.adminos.api.infrastructure.push.B64.decode(privateKey))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("VAPID_PRIVATE_KEY is not a P-256 private key (URL-safe base64 of 32 bytes)", e)
        }
        val derived = dev.adminos.api.infrastructure.push.B64.encode(
            dev.adminos.api.infrastructure.push.P256.encode(dev.adminos.api.infrastructure.push.P256.publicOf(private)))
        require(derived == publicKey.trim().trimEnd('=')) { "VAPID_PUBLIC_KEY doesn't match VAPID_PRIVATE_KEY" }
    }

    /** Never print the private key. */
    override fun toString() = "VapidConfig(publicKey=$publicKey, subject=$subject)"

    companion object {
        internal fun from(getenv: (String) -> String?): VapidConfig? {
            fun value(key: String) = getenv(key)?.trim()?.takeIf { it.isNotEmpty() }
            val public = value("VAPID_PUBLIC_KEY")
            val private = value("VAPID_PRIVATE_KEY")
            if (public == null && private == null) return null
            require(public != null && private != null) { "Set both VAPID_PUBLIC_KEY and VAPID_PRIVATE_KEY (./gradlew -q vapidKeys), or neither" }
            val subject = value("VAPID_SUBJECT") ?: throw IllegalArgumentException("VAPID_SUBJECT is required with VAPID keys, e.g. mailto:you@example.com")
            return VapidConfig(public.trimEnd('='), private, subject)
        }
    }
}

/**
 * Object storage for the documents vault (AOS-507): Cloudflare R2, or any S3-compatible store.
 * Off unless DOCS_S3_BUCKET is set; once it is, the rest must be valid or start-up stops.
 * For R2: endpoint `https://<account id>.r2.cloudflarestorage.com`, region `auto`.
 */
data class DocumentStorageConfig(
    val endpoint: String,
    val region: String,
    val bucket: String,
    val accessKeyId: String,
    val secretAccessKey: String
) {
    init {
        require(endpoint.startsWith("https://") || endpoint.startsWith("http://127.0.0.1") || endpoint.startsWith("http://localhost")) {
            "DOCS_S3_ENDPOINT must be https (plain http only for a local test store)"
        }
        require(bucket.matches(Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"))) { "DOCS_S3_BUCKET is not a valid bucket name" }
        require(region.isNotBlank()) { "DOCS_S3_REGION is blank" }
    }

    /** Never print the secret. */
    override fun toString() = "DocumentStorageConfig(endpoint=$endpoint, region=$region, bucket=$bucket, accessKeyId=$accessKeyId)"

    companion object {
        internal fun from(getenv: (String) -> String?): DocumentStorageConfig? {
            fun value(key: String) = getenv(key)?.trim()?.takeIf { it.isNotEmpty() }
            val bucket = value("DOCS_S3_BUCKET") ?: return null
            fun required(key: String) = value(key) ?: throw IllegalArgumentException("$key is required when DOCS_S3_BUCKET is set")
            return DocumentStorageConfig(
                endpoint = required("DOCS_S3_ENDPOINT").trimEnd('/'),
                region = value("DOCS_S3_REGION") ?: "auto",
                bucket = bucket,
                accessKeyId = required("DOCS_S3_ACCESS_KEY_ID"),
                secretAccessKey = required("DOCS_S3_SECRET_ACCESS_KEY")
            )
        }
    }
}

