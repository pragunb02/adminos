package dev.adminos.api.infrastructure.queue

import io.lettuce.core.RedisURI

/**
 * The queue publisher's Redis connection (AOS-634). Redis requires a password (`requirepass`, from
 * REDIS_PASSWORD), so a connection without one is refused at start-up rather than failing on the first job.
 *
 * The password comes from REDIS_PASSWORD (compose) or from REDIS_URL's user info (a host-run API); if both are
 * set they must agree. Neither the URL, the password nor the parser's message (which may quote the URL) ever
 * reaches a log or an error.
 */
object RedisUrl {
    /**
     * Lettuce's view of [url] with the password applied.
     * @throws IllegalStateException when the URL doesn't parse, there is no password, or the two disagree
     */
    fun parse(url: String, password: String?): RedisURI {
        val uri = try {
            RedisURI.create(url.trim())
        } catch (e: RuntimeException) {
            // No cause: its message can quote the URL, password included.
            throw IllegalStateException("REDIS_URL is not a valid Redis URL (value not shown)")
        }
        val inUrl = uri.credentialsProvider?.resolveCredentialsAsync()?.toCompletableFuture()?.getNow(null)
            ?.takeIf { it.hasPassword() && it.password.isNotEmpty() }?.password?.let { String(it) }
        val fromEnv = password?.takeIf { it.isNotBlank() }
        check(inUrl != null || fromEnv != null) {
            "Redis requires a password: set REDIS_PASSWORD (openssl rand -hex 32) in .env (value not shown)"
        }
        check(inUrl == null || fromEnv == null || inUrl == fromEnv) {
            "REDIS_URL's password and REDIS_PASSWORD disagree; set only one (values not shown)"
        }
        if (inUrl == null) uri.setAuthentication(fromEnv!!)
        return uri
    }
}
