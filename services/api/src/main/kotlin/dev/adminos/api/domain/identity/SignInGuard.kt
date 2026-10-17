package dev.adminos.api.domain.identity

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Lockout with exponential backoff for refused sign-ins (AOS-618, AUD-16, ASVS V6.3 / WSTG-ATHN-03).
 *
 * Google proves who signs in, so there is no password to guess; this stops a refused caller (a stranger's Google
 * account, forged codes, replayed states) from hammering the sign-in route and Google on our behalf.
 * - After [freeFailures] refusals within [window] from one client, that client is locked for [baseLock], doubling with
 *   each further refusal up to [maxLock]. A successful owner sign-in clears the client's record.
 * - Bounded: at most [maxClients] clients are tracked; stale and then the oldest records are dropped first.
 * - The client key is the client IP. It is only as good as `TRUST_FORWARDED_FOR` makes it (AUD-03): without a
 *   trusted edge every caller shares the proxy's address, so a stranger can delay the owner's sign-in, never bypass it.
 *   The private network (AOS-616) is what keeps strangers from reaching the route at all.
 */
class SignInGuard(
    private val clock: Clock = Clock.systemUTC(),
    private val freeFailures: Int = 5,
    private val window: Duration = Duration.ofMinutes(15),
    private val baseLock: Duration = Duration.ofMinutes(1),
    private val maxLock: Duration = Duration.ofHours(1),
    private val maxClients: Int = 10_000
) {
    init {
        require(freeFailures >= 1) { "freeFailures must be at least 1" }
        require(!baseLock.isNegative && !baseLock.isZero && maxLock >= baseLock) { "lock durations must be positive and ordered" }
    }

    private data class Record(val failures: Int, val firstAt: Instant, val lastAt: Instant, val lockedUntil: Instant?)

    private val records = ConcurrentHashMap<String, Record>()

    /** How long [client] must still wait, or null if it may try now. */
    fun lockedFor(client: String): Duration? {
        val until = records[client]?.lockedUntil ?: return null
        val left = Duration.between(clock.instant(), until)
        return left.takeIf { !it.isNegative && !it.isZero }
    }

    /** Records a refused sign-in; returns the lock it now carries (null while still within the free attempts). */
    fun refused(client: String): Duration? {
        val now = clock.instant()
        if (records.size >= maxClients) evict(now)
        val r = records.compute(client) { _, old ->
            val live = old?.takeIf { Duration.between(it.lastAt, now) <= window }
            val failures = (live?.failures ?: 0) + 1
            val over = failures - freeFailures
            Record(failures, live?.firstAt ?: now, now, if (over <= 0) null else now.plus(backoff(over)))
        }
        return r?.lockedUntil?.let { Duration.between(now, it) }
    }

    /** The owner signed in from [client]: forget its refusals. */
    fun succeeded(client: String) {
        records.remove(client)
    }

    /** baseLock × 2^(over−1), capped at [maxLock]. */
    private fun backoff(over: Int): Duration {
        val shift = (over - 1).coerceAtMost(30)
        val millis = baseLock.toMillis().toBigInteger().shiftLeft(shift)
        return if (millis > maxLock.toMillis().toBigInteger()) maxLock else Duration.ofMillis(millis.toLong())
    }

    private fun evict(now: Instant) {
        records.entries.removeIf { (_, r) -> Duration.between(r.lastAt, now) > window && (r.lockedUntil == null || r.lockedUntil <= now) }
        val excess = records.size - maxClients + 1
        if (excess > 0) records.entries.sortedBy { it.value.lastAt }.take(excess).forEach { records.remove(it.key, it.value) }
    }

    val trackedClients: Int get() = records.size
}
