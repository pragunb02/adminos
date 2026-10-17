package dev.adminos.api.domain.ask

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-user limits on questions (AOS-710), checked before any model call: at most [perHour] in any rolling hour
 * and [perDay] in any rolling day. Each question costs up to two model calls, so this bounds both spend (the
 * monthly cap still applies, D-68) and how fast a stolen session could probe the feature.
 * In memory per instance, like the other API rate limits; the cost cap is the cross-instance backstop.
 */
class AskQuota(private val clock: Clock, private val perHour: Int = PER_HOUR, private val perDay: Int = PER_DAY) {
    init {
        require(perHour in 1..perDay) { "1 <= perHour <= perDay" }
    }

    /** Taken, or refused with how long until the next question is allowed. */
    sealed interface Decision {
        data object Taken : Decision

        data class Refused(val retryAfter: Duration) : Decision
    }

    private val asked = ConcurrentHashMap<UUID, List<Instant>>()

    /** Takes one question for [userId] if both limits allow it (atomic per user). */
    fun take(userId: UUID): Decision {
        val now = clock.instant()
        var decision: Decision = Decision.Taken
        asked.compute(userId) { _, before ->
            val recent = before.orEmpty().filter { it.isAfter(now.minus(DAY)) }
            val lastHour = recent.filter { it.isAfter(now.minus(HOUR)) }
            decision = when {
                recent.size >= perDay -> Decision.Refused(Duration.between(now, recent[recent.size - perDay].plus(DAY)))
                lastHour.size >= perHour -> Decision.Refused(Duration.between(now, lastHour[lastHour.size - perHour].plus(HOUR)))
                else -> Decision.Taken
            }
            if (decision == Decision.Taken) recent + now else recent
        }
        return decision
    }

    companion object {
        const val PER_HOUR = 30
        const val PER_DAY = 150
        private val HOUR: Duration = Duration.ofHours(1)
        private val DAY: Duration = Duration.ofDays(1)
    }
}
