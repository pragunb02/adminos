package dev.adminos.api.domain.ingestion.fallback

import dev.adminos.api.domain.ingestion.events.IngestEvent
import dev.adminos.api.domain.ingestion.events.IngestEventService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID

/** Told when the user confirms an AI reading: the reading was right (template learning listens, AOS-706). */
fun interface ProposalConfirmedListener {
    suspend fun confirmed(proposal: MessageProposal)
}

/** The outcome of confirming a proposal. */
sealed interface ConfirmOutcome {
    /** Now an intake event ([eventId]); [duplicate] when the same message was already in the intake (then nothing was added). */
    data class Confirmed(val eventId: UUID, val duplicate: Boolean) : ConfirmOutcome

    /** Not the user's, or already decided. */
    data object NotFound : ConfirmOutcome
}

/**
 * Review for messages a model read (AOS-705, D-106). Owner-scoped: every call takes the signed-in user's id, so
 * another user's proposal is simply not found.
 * - **confirm** turns the proposal into an ordinary intake event (the pipeline then dedups, links transfers and
 *   categorises it as usual), with the message's own fingerprint as `sourceRef` and its provenance in metadata
 *   (`extractedBy = ai:<model>:<prompt>`, `aiRead = confirmed`);
 * - **dismiss** keeps the proposal as dismissed, so a resent message isn't read again; nothing reaches the totals.
 */
class MessageReviewService(
    private val proposals: MessageProposalRepository,
    private val intake: IngestEventService,
    private val clock: Clock = Clock.systemUTC(),
    /** Told about every confirmed reading (template learning, AOS-706). A failing listener never undoes a confirmation. */
    private val listeners: List<ProposalConfirmedListener> = emptyList()
) {
    private val logger = LoggerFactory.getLogger(MessageReviewService::class.java)

    suspend fun pending(userId: UUID): List<MessageProposal> = proposals.pending(userId, MAX_LIST)

    suspend fun confirm(userId: UUID, id: UUID): ConfirmOutcome {
        val p = proposals.find(userId, id)?.takeIf { it.status == ProposalStatus.PENDING } ?: return ConfirmOutcome.NotFound
        val event = IngestEvent(
            userId = userId,
            source = p.channel.source,
            sourceRef = p.sourceRef,
            occurredAt = p.occurredAt,
            direction = p.direction,
            amount = p.amount,
            merchantRaw = p.merchant,
            accountHint = p.accountLast4,
            metadata = listOfNotNull(
                p.bankCode?.let { "bank" to it },
                "extractedBy" to p.extractedBy,
                "aiRead" to "confirmed"
            ).toMap()
        )
        // Decide first, which only one click (confirm or dismiss) can win; if the intake then fails, the proposal is
        // reopened, so it is never confirmed without its event. The event's sourceRef makes a repeat a duplicate.
        if (!proposals.decide(userId, id, ProposalStatus.CONFIRMED, clock.instant(), event.id)) return ConfirmOutcome.NotFound
        val accepted = try {
            intake.ingest(listOf(event)).accepted
        } catch (e: Exception) {
            withContext(NonCancellable) { proposals.reopen(userId, id) }
            throw e
        }
        val confirmed = p.copy(status = ProposalStatus.CONFIRMED, decidedAt = clock.instant(), ingestEventId = event.id)
        for (l in listeners) {
            try {
                l.confirmed(confirmed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("A listener failed after confirming proposal {} ({})", id, e.javaClass.simpleName)
            }
        }
        return ConfirmOutcome.Confirmed(event.id, duplicate = accepted == 0)
    }

    suspend fun dismiss(userId: UUID, id: UUID): Boolean = proposals.decide(userId, id, ProposalStatus.DISMISSED, clock.instant(), null)

    companion object {
        const val MAX_LIST = 100
    }
}
