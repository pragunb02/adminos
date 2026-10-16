package dev.adminos.api.domain.scheduling

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * When a job is due (AOS-501). A schedule divides time into **slots**; each slot runs at most once
 * (see [JobRunRepository.claim]). The scheduler only asks for the latest slot that has started, so
 * after downtime a job catches up **once**, not once per missed slot.
 */
sealed interface Schedule {
    /** The start of the most recent slot at or before [now]. */
    fun latestSlot(now: Instant): Instant

    /** Human-readable form for logs, e.g. "daily at 09:00 Asia/Kolkata". */
    fun describe(): String

    /** Once a day at [at] local time in [zone] (daylight-saving gaps resolve to the next valid time). */
    data class Daily(val at: LocalTime, val zone: ZoneId) : Schedule {
        override fun latestSlot(now: Instant): Instant {
            val today = now.atZone(zone).toLocalDate()
            val todaysSlot = today.atTime(at).atZone(zone).toInstant()
            return if (todaysSlot <= now) todaysSlot else today.minusDays(1).atTime(at).atZone(zone).toInstant()
        }

        override fun describe() = "daily at $at $zone"
    }

    /** Every [period], aligned to the Unix epoch so every instance agrees on the slot boundaries. */
    data class Every(val period: Duration) : Schedule {
        init {
            require(period >= Duration.ofMinutes(1)) { "A schedule period must be at least one minute, was $period" }
        }

        override fun latestSlot(now: Instant): Instant {
            val periodMillis = period.toMillis()
            return Instant.ofEpochMilli(Math.floorDiv(now.toEpochMilli(), periodMillis) * periodMillis)
        }

        override fun describe() = "every $period"
    }
}
