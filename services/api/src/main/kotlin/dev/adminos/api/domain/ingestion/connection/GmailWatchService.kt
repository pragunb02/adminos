package dev.adminos.api.domain.ingestion.connection

import dev.adminos.api.domain.scheduling.JobName
import dev.adminos.api.domain.scheduling.JobResult
import dev.adminos.api.domain.scheduling.Schedule
import dev.adminos.api.domain.scheduling.ScheduledJob
import dev.adminos.api.domain.scheduling.forEachIsolated
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

/** Gmail push settings (AOS-405). A blank [topic] turns push registration off. */
data class GmailWatchSettings(
    val topic: String,
    /** A watch ending within this window is renewed (Gmail's watches last at most 7 days). */
    val renewWithin: Duration = Duration.ofHours(24),
    val clock: Clock = Clock.systemUTC()
) {
    init {
        require(topic.isBlank() || topic.matches(TOPIC)) {
            "GOOGLE_GMAIL_PUBSUB_TOPIC must look like projects/<project>/topics/<topic>"
        }
        require(!renewWithin.isNegative && renewWithin < Duration.ofDays(7)) { "renewWithin must be under 7 days, was $renewWithin" }
    }

    val enabled get() = topic.isNotBlank()

    companion object {
        private val TOPIC = Regex("projects/[^/\\s]+/topics/[^/\\s]+")
    }
}

/** What one registration attempt did. */
sealed interface WatchOutcome {
    data class Registered(val watch: GmailWatch) : WatchOutcome
    /** Recorded on the connection and retried by the next renewal run; the connection stays as it was. */
    data class Failed(val reason: String) : WatchOutcome
    /** Push is off (no topic configured), or the connection isn't a connected Gmail one. */
    data object Skipped : WatchOutcome
}

/** What a renewal run did, as counts. */
data class WatchRenewalResult(val connections: Int, val renewed: Int, val notDue: Int, val failed: Int, val disabled: Boolean = false)

/**
 * Gmail push registration (AOS-405, D-63): `users.watch` after connecting, renewal before the
 * watch lapses, and `users.stop` on disconnect.
 *
 * - **Never disconnects** on a failed watch: the failure is recorded on the connection
 *   (`watch_error`, `watch_checked_at`) and the next renewal run retries; the daily fallback sync
 *   (D-59) keeps mail flowing meanwhile. Only a refused refresh token marks the connection `error`.
 * - **Only connected Gmail connections** are registered or renewed, so a disconnected or revoked
 *   one stops being renewed and its watch lapses on its own.
 * - **Without a topic** nothing is called; that is logged once, at start-up.
 */
class GmailWatchService(
    private val connections: ConnectionRepository,
    private val api: GmailPushApi,
    private val tokens: GmailAccessTokens,
    private val settings: GmailWatchSettings
) {
    private val logger = LoggerFactory.getLogger(GmailWatchService::class.java)

    init {
        if (!settings.enabled) {
            logger.warn("GOOGLE_GMAIL_PUBSUB_TOPIC not set: Gmail push is off; the daily fallback sync still runs (AOS-405)")
        }
    }

    /** Registers (or re-registers) the connection's watch now, recording the result on it. Never throws a Google failure. */
    suspend fun register(connection: UserConnection): WatchOutcome {
        if (!settings.enabled || connection.sourceType != SourceType.GMAIL || connection.status != ConnectionStatus.CONNECTED) {
            return WatchOutcome.Skipped
        }
        val now = settings.clock.instant()
        return try {
            val registration = watchWithFreshToken(connection)
            val watch = GmailWatch(registration.expiresAt, registration.historyId, error = null, checkedAt = now)
            connections.updateWatch(connection.id, watch)
            logger.info("Gmail push watch set for connection {} until {}", connection.id, registration.expiresAt)
            WatchOutcome.Registered(watch)
        } catch (e: GmailPushException) {
            failed(connection, reasonFor(e), e)
        } catch (e: GmailAccessException) {
            failed(connection, e.message.orEmpty(), e)
        }
    }

    /** Renews every connected Gmail watch that is missing, expired, or ends within the renewal window. */
    suspend fun renewDue(): WatchRenewalResult {
        if (!settings.enabled) return WatchRenewalResult(0, 0, 0, 0, disabled = true)
        val all = connections.findConnectedBySourceType(SourceType.GMAIL)
        val now = settings.clock.instant()
        val due = all.filter { it.watch.needsRenewal(now, settings.renewWithin) }
        var failed = 0
        val counts = forEachIsolated(due, logger, { "gmail watch ${it.id}" }) { connection ->
            if (register(connection) is WatchOutcome.Failed) failed++
        }
        val result = WatchRenewalResult(
            connections = all.size,
            renewed = counts.succeeded - failed,
            notDue = all.size - due.size,
            failed = failed + counts.failed
        )
        logger.info("Gmail watch renewal: connections={}, renewed={}, notDue={}, failed={}",
            result.connections, result.renewed, result.notDue, result.failed)
        return result
    }

    /**
     * Best effort `users.stop` before a disconnect wipes the tokens: Gmail stops publishing at once
     * instead of when the watch lapses. Any failure is logged and ignored (pushes for a disconnected
     * connection are acknowledged and dropped anyway).
     */
    suspend fun stop(connection: UserConnection) {
        if (!settings.enabled || connection.sourceType != SourceType.GMAIL) return
        if (!connection.watch.isActive(settings.clock.instant())) return
        try {
            api.stop(tokens.current(connection))
            logger.info("Gmail push watch stopped for connection {}", connection.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GmailPushException) {
            logger.warn("Could not stop the Gmail watch for connection {}: {}", connection.id, e.message)
        } catch (e: GmailAccessException) {
            logger.warn("Could not stop the Gmail watch for connection {}: {}", connection.id, e.message)
        }
    }

    /** One retry with a refreshed token when Gmail refuses the stored one (it may have been revoked early). */
    private suspend fun watchWithFreshToken(connection: UserConnection): WatchRegistration = try {
        api.watch(tokens.current(connection), settings.topic)
    } catch (e: GmailPushException.Unauthorized) {
        api.watch(tokens.refreshed(connection), settings.topic)
    }

    private suspend fun failed(connection: UserConnection, reason: String, e: Exception): WatchOutcome.Failed {
        val now = settings.clock.instant()
        connections.updateWatch(connection.id, connection.watch.copy(error = reason, checkedAt = now))
        logger.warn("Gmail push watch failed for connection {}: {}", connection.id, e.message)
        return WatchOutcome.Failed(reason)
    }

    private fun reasonFor(e: GmailPushException) = when (e) {
        is GmailPushException.Unauthorized -> "Gmail push: Gmail did not accept the access token"
        is GmailPushException.Rejected -> "Gmail push: Gmail answered HTTP ${e.status}"
        is GmailPushException.Unavailable -> "Gmail push: Gmail is unreachable"
    }
}

/** Every 6 hours: renews Gmail push watches ending within a day, and sets missing ones (AOS-405, D-50). */
class GmailWatchRenewalJob(private val watches: GmailWatchService) : ScheduledJob {
    override val name = JobName("gmail-watch-renewal")
    override val schedule = Schedule.Every(Duration.ofHours(6))

    override suspend fun run(): JobResult {
        val r = watches.renewDue()
        if (r.disabled) return JobResult.of("disabled" to 1)
        return JobResult.of("connections" to r.connections, "renewed" to r.renewed, "notDue" to r.notDue, "failed" to r.failed)
    }
}
