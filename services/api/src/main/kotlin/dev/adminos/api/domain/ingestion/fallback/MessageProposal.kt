package dev.adminos.api.domain.ingestion.fallback

import dev.adminos.api.domain.ingestion.events.IngestDirection
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class ProposalStatus { PENDING, CONFIRMED, DISMISSED }

/**
 * A transaction a model read from a bank message, waiting in Review (AOS-705, D-106). It is **not** an intake
 * event and not in any total until the user confirms it; then it becomes an ordinary intake event marked as
 * AI-read. Structured, grounded fields only: no message text (D-33).
 */
data class MessageProposal(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val channel: MessageChannel,
    /** The message's fingerprint, reused as the intake event's `sourceRef` on confirmation. */
    val sourceRef: String,
    val occurredAt: Instant,
    val direction: IngestDirection,
    val amount: BigDecimal,
    val accountLast4: String?,
    val merchant: String?,
    val bankCode: String?,
    /** Which model and prompt read it: `extractedBy = ai:<model>:<promptVersion>` on the event. */
    val model: String,
    val promptVersion: String,
    val status: ProposalStatus = ProposalStatus.PENDING,
    val createdAt: Instant,
    val decidedAt: Instant? = null,
    val ingestEventId: UUID? = null
) {
    init {
        require(sourceRef.isNotBlank() && sourceRef.length <= 255) { "sourceRef must be 1..255 characters" }
        require(amount.signum() > 0 && amount.scale() <= 2) { "amount must be positive with at most 2 decimals" }
        require(accountLast4 == null || accountLast4.matches(Regex("[0-9]{4}"))) { "accountLast4 must be 4 digits" }
        require(merchant == null || merchant.length in 2..ReadMessageTask.MAX_MERCHANT) { "merchant must be 2..${ReadMessageTask.MAX_MERCHANT} characters" }
        require((status == ProposalStatus.PENDING) == (decidedAt == null)) { "only a decided proposal has a decision time" }
        require(ingestEventId == null || status == ProposalStatus.CONFIRMED) { "only a confirmed proposal has an intake event" }
    }

    /** The provenance written on the intake event (research §6). */
    val extractedBy: String get() = "ai:$model:$promptVersion"

    override fun toString() = "MessageProposal(id=$id, channel=$channel, status=$status)" // no amounts or names in logs
}

interface MessageProposalRepository {
    /** Stores [proposal] unless one exists for its (user, channel, sourceRef); true when stored. */
    suspend fun saveNew(proposal: MessageProposal): Boolean

    /** Whether this message was already read (in any status), so a resent message isn't asked about again. */
    suspend fun exists(userId: UUID, channel: MessageChannel, sourceRef: String): Boolean

    /** The user's pending proposals, newest message first. */
    suspend fun pending(userId: UUID, limit: Int): List<MessageProposal>

    /** The user's proposal [id], or null (another user's proposal is not found). */
    suspend fun find(userId: UUID, id: UUID): MessageProposal?

    /**
     * Moves the user's proposal [id] from PENDING to [status], atomically; false when it isn't the user's or was
     * already decided (so two clicks can't both win).
     */
    suspend fun decide(userId: UUID, id: UUID, status: ProposalStatus, at: Instant, ingestEventId: UUID?): Boolean

    /** Moves a CONFIRMED proposal back to PENDING (its intake event could not be stored); false otherwise. */
    suspend fun reopen(userId: UUID, id: UUID): Boolean
}

class InMemoryMessageProposalRepository : MessageProposalRepository {
    private val rows = ConcurrentHashMap<UUID, MessageProposal>()
    private val lock = Any()

    override suspend fun saveNew(proposal: MessageProposal): Boolean = synchronized(lock) {
        if (rows.values.any { it.userId == proposal.userId && it.channel == proposal.channel && it.sourceRef == proposal.sourceRef }) return@synchronized false
        rows[proposal.id] = proposal
        true
    }

    override suspend fun exists(userId: UUID, channel: MessageChannel, sourceRef: String): Boolean =
        rows.values.any { it.userId == userId && it.channel == channel && it.sourceRef == sourceRef }

    override suspend fun pending(userId: UUID, limit: Int): List<MessageProposal> =
        rows.values.filter { it.userId == userId && it.status == ProposalStatus.PENDING }
            .sortedWith(compareByDescending<MessageProposal> { it.occurredAt }.thenBy { it.id }).take(limit)

    override suspend fun find(userId: UUID, id: UUID): MessageProposal? = rows[id]?.takeIf { it.userId == userId }

    override suspend fun decide(userId: UUID, id: UUID, status: ProposalStatus, at: Instant, ingestEventId: UUID?): Boolean = synchronized(lock) {
        require(status != ProposalStatus.PENDING) { "A decision confirms or dismisses" }
        val row = rows[id]?.takeIf { it.userId == userId && it.status == ProposalStatus.PENDING } ?: return@synchronized false
        rows[id] = row.copy(status = status, decidedAt = at, ingestEventId = ingestEventId)
        true
    }

    override suspend fun reopen(userId: UUID, id: UUID): Boolean = synchronized(lock) {
        val row = rows[id]?.takeIf { it.userId == userId && it.status == ProposalStatus.CONFIRMED } ?: return@synchronized false
        rows[id] = row.copy(status = ProposalStatus.PENDING, decidedAt = null, ingestEventId = null)
        true
    }
}
