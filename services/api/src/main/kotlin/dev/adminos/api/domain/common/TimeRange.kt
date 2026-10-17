package dev.adminos.api.domain.common

import java.time.Instant

/** A half-open span of time, [from, until): one type instead of two adjacent Instants (ENGINEERING §2). */
data class TimeRange(val from: Instant, val until: Instant) {
    init {
        require(from < until) { "a time range must end after it starts" }
    }

    operator fun contains(instant: Instant): Boolean = instant >= from && instant < until
}
