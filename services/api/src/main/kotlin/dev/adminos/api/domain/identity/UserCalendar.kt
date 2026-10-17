package dev.adminos.api.domain.identity

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * A user's own dates (AOS-503): "today", "3 days before the due date" and "overdue" mean the
 * user's local calendar, not the server's. A missing user or an invalid zone falls back to India
 * time (the owner's), logged.
 */
class UserCalendar(private val users: UserRepository, private val clock: Clock = Clock.systemUTC()) {
    private val logger = LoggerFactory.getLogger(UserCalendar::class.java)

    suspend fun zoneOf(userId: UUID): ZoneId {
        val id = users.findById(userId)?.timezone ?: return DEFAULT_ZONE
        return try {
            ZoneId.of(id)
        } catch (e: DateTimeException) {
            logger.warn("User {} has an invalid time zone; using {}", userId, DEFAULT_ZONE)
            DEFAULT_ZONE
        }
    }

    suspend fun today(userId: UUID): LocalDate = LocalDate.now(clock.withZone(zoneOf(userId)))

    /** The server's own date, for coarse database pre-filters that are then refined per user. */
    fun serverToday(): LocalDate = LocalDate.now(clock.withZone(DEFAULT_ZONE))

    companion object {
        val DEFAULT_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    }
}
