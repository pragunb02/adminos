package dev.adminos.api.domain.ingestion.webhook

import dev.adminos.api.domain.ingestion.connection.ConnectionService
import dev.adminos.api.domain.ingestion.connection.ConnectionStatus
import dev.adminos.api.domain.ingestion.sync.IngestionService
import dev.adminos.api.domain.ingestion.sync.SyncSession
import dev.adminos.api.domain.ingestion.sync.SyncType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** What a verified Gmail push did (AOS-630). */
sealed interface GmailPushOutcome {
    /** No Gmail connection has this address. */
    data object NoConnection : GmailPushOutcome

    /** The connection exists but isn't connected. */
    data object NotActive : GmailPushOutcome

    /** The change it announces is at or before the history ID the last sync stored: nothing new to read. */
    data object AlreadySynced : GmailPushOutcome

    /** A sync of this connection is already queued and hasn't started: it will read this change too. */
    data class Coalesced(val pendingSessionId: UUID) : GmailPushOutcome

    data class Queued(val session: SyncSession) : GmailPushOutcome
}

/**
 * Turns a verified Gmail push into at most one waiting sync per connection (AOS-630, AUD-27).
 *
 * A Pub/Sub push token stays valid for about an hour, and the push body isn't bound to it, so a captured token can be
 * replayed with any body. Every sync is incremental and idempotent, but replays must not multiply them:
 *  - a push whose history ID is at or before the one the last sync stored announces nothing new ([AlreadySynced]);
 *  - while a sync of the connection is **queued and not yet started**, another push adds nothing: that sync starts
 *    from the stored history ID and reads everything up to the mailbox's position when it runs ([Coalesced]).
 *    Once the worker has started it (`in_progress`), a new push queues the next one, so mail that arrives during a
 *    sync is never left for the daily fallback;
 *  - a queued sync older than [PENDING_WINDOW] is treated as lost (the queue dropped it) and no longer coalesces.
 *
 * So a replay can cause at most one running and one waiting sync per connection at any time. The check and the
 * enqueue run under one lock, so two pushes at the same moment can't both queue.
 */
class GmailPushService(
    private val connections: ConnectionService,
    private val ingestion: IngestionService,
    private val clock: Clock = Clock.systemUTC()
) {
    companion object {
        val PENDING_WINDOW: Duration = Duration.ofMinutes(10)
    }

    private val logger = LoggerFactory.getLogger(GmailPushService::class.java)
    private val lock = Mutex()

    suspend fun onPush(emailAddress: String, historyId: Long?): GmailPushOutcome {
        val connection = connections.findByGmailAddress(emailAddress) ?: return GmailPushOutcome.NoConnection
        if (connection.status != ConnectionStatus.CONNECTED) return GmailPushOutcome.NotActive
        val stored = connection.historyId?.toBigIntegerOrNull()
        if (historyId != null && stored != null && BigInteger.valueOf(historyId) <= stored) {
            return GmailPushOutcome.AlreadySynced
        }
        return lock.withLock {
            val pending = ingestion.pendingGmailSync(connection.id, queuedAfter = clock.instant().minus(PENDING_WINDOW))
            if (pending != null) {
                logger.info("Gmail push for connection {} coalesced into queued sync {}", connection.id, pending.id)
                GmailPushOutcome.Coalesced(pending.id)
            } else {
                // A push is always an incremental sync (AOS-405): the worker starts from the history ID stored on the
                // connection; the notification's ID is passed along for information only (D-59).
                GmailPushOutcome.Queued(ingestion.enqueueGmailIngest(
                    userId = connection.userId,
                    connectionId = connection.id,
                    historyId = historyId?.toString(),
                    syncType = SyncType.INCREMENTAL
                ))
            }
        }
    }
}
