package dev.adminos.api.domain.ingestion.connection

import java.time.Duration
import java.time.Instant

/**
 * A connection's Gmail push registration (`users.watch`, AOS-405, D-63). Gmail publishes mailbox
 * changes to our Pub/Sub topic until [expiresAt] (at most 7 days), so it is renewed before then.
 *
 * - [historyId]: the mailbox position Gmail reported at registration. Informational only: a sync
 *   always starts from the connection's own history ID (D-59).
 * - [error]: why the last attempt failed, short and content-free; null after a success.
 * - [checkedAt]: when registration was last attempted.
 */
data class GmailWatch(
    val expiresAt: Instant? = null,
    val historyId: String? = null,
    val error: String? = null,
    val checkedAt: Instant? = null
) {
    /** True when there is no watch, or it ends within [window] of [now] (or has already ended). */
    fun needsRenewal(now: Instant, window: Duration): Boolean = expiresAt == null || expiresAt <= now.plus(window)

    /** True while Gmail is still publishing changes for this connection. */
    fun isActive(now: Instant): Boolean = expiresAt != null && expiresAt > now

    companion object {
        val NONE = GmailWatch()
    }
}
