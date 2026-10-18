package dev.adminos.api.domain.ingestion.fallback.templates

import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.events.IngestEvent
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.fallback.FallbackOffer
import dev.adminos.api.domain.ingestion.fallback.MessageChannel
import dev.adminos.api.domain.ingestion.fallback.MessageGrounding
import dev.adminos.api.domain.ingestion.fallback.MessageProposal
import dev.adminos.api.domain.ingestion.fallback.MessageQuestion
import dev.adminos.api.domain.ingestion.fallback.ProposalConfirmedListener
import dev.adminos.api.domain.ingestion.fallback.UnrecognisedMessage
import dev.adminos.api.domain.ingestion.fallback.UnrecognisedMessageSink
import dev.adminos.api.domain.ingestion.fallback.occurredAt
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface MessageTemplateRepository {
    suspend fun save(template: MessageTemplate)

    /** Replaces the user's template with the same id; false when there is none. */
    suspend fun update(template: MessageTemplate): Boolean

    /** The user's templates (any status) for one sender and direction: the candidates a new shape may join. */
    suspend fun forFormat(userId: UUID, channel: MessageChannel, bankCode: String?, direction: IngestDirection): List<MessageTemplate>

    /** The user's approved templates for one sender, most used first. */
    suspend fun approved(userId: UUID, channel: MessageChannel, bankCode: String?): List<MessageTemplate>

    /** The user's templates waiting for a decision, most seen first. */
    suspend fun proposed(userId: UUID, limit: Int): List<MessageTemplate>

    /** Moves the user's PROPOSED template [id] to [status] (APPROVED or REJECTED), atomically; false otherwise. */
    suspend fun decide(userId: UUID, id: UUID, status: TemplateStatus, at: Instant): Boolean

    /** Counts one message read by template [id]. */
    suspend fun hit(id: UUID)
}

class InMemoryMessageTemplateRepository : MessageTemplateRepository {
    private val rows = ConcurrentHashMap<UUID, MessageTemplate>()
    private val lock = Any()

    override suspend fun save(template: MessageTemplate) { rows[template.id] = template }

    override suspend fun update(template: MessageTemplate): Boolean = synchronized(lock) {
        if (rows[template.id]?.userId != template.userId) return@synchronized false
        rows[template.id] = template
        true
    }

    override suspend fun forFormat(userId: UUID, channel: MessageChannel, bankCode: String?, direction: IngestDirection) =
        rows.values.filter { it.userId == userId && it.channel == channel && it.bankCode == bankCode && it.direction == direction }.sortedBy { it.createdAt }

    override suspend fun approved(userId: UUID, channel: MessageChannel, bankCode: String?) =
        rows.values.filter { it.userId == userId && it.channel == channel && it.bankCode == bankCode && it.status == TemplateStatus.APPROVED }
            .sortedWith(compareByDescending<MessageTemplate> { it.hits }.thenBy { it.createdAt })

    override suspend fun proposed(userId: UUID, limit: Int) =
        rows.values.filter { it.userId == userId && it.status == TemplateStatus.PROPOSED }
            .sortedWith(compareByDescending<MessageTemplate> { it.seen }.thenBy { it.createdAt }).take(limit)

    override suspend fun decide(userId: UUID, id: UUID, status: TemplateStatus, at: Instant): Boolean = synchronized(lock) {
        require(status == TemplateStatus.APPROVED || status == TemplateStatus.REJECTED) { "A decision approves or rejects" }
        val row = rows[id]?.takeIf { it.userId == userId && it.status == TemplateStatus.PROPOSED } ?: return@synchronized false
        rows[id] = row.copy(status = status, updatedAt = at)
        true
    }

    override suspend fun hit(id: UUID): Unit = synchronized(lock) { rows[id]?.let { rows[id] = it.copy(hits = it.hits + 1) } }
}

/**
 * Learns message formats from **confirmed** AI readings (AOS-706, D-107, LILAC-style: a cache of learnt templates in
 * front of the model). Each confirmation adds its proposal's masked shape to a matching format of the same sender and
 * direction (a Drain merge, [MessageShapes.merge]) or starts a new one. Once a format is seen [minSeen] times it is
 * PROPOSED in Review; only the user approves it. An approved or rejected format's shape never changes (what was
 * approved is what reads).
 */
class TemplateLearning(
    private val templates: MessageTemplateRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val minSeen: Int = MIN_SEEN
) : ProposalConfirmedListener {
    init {
        require(minSeen >= 2) { "A format is proposed after at least 2 confirmations" }
    }

    override suspend fun confirmed(proposal: MessageProposal) {
        val shape = proposal.shape?.takeIf(TemplateReading::isReadable) ?: return
        val now = clock.instant()
        for (t in templates.forFormat(proposal.userId, proposal.channel, proposal.bankCode, proposal.direction)) {
            val merged = MessageShapes.merge(t.shape, shape) ?: continue
            val seen = t.seen + 1
            val settled = t.status == TemplateStatus.APPROVED || t.status == TemplateStatus.REJECTED
            val status = if (t.status == TemplateStatus.OBSERVING && seen >= minSeen) TemplateStatus.PROPOSED else t.status
            templates.update(t.copy(shape = if (settled) t.shape else merged, seen = seen, status = status, updatedAt = now))
            return
        }
        templates.save(MessageTemplate(
            userId = proposal.userId, channel = proposal.channel, bankCode = proposal.bankCode, direction = proposal.direction,
            shape = shape, createdAt = now
        ))
    }

    companion object {
        const val MIN_SEEN = 3
    }
}

/**
 * The first reader of an unrecognised message (AOS-706, D-107): the user's approved templates for its sender, before
 * any model. A template that reads the message (and passes the same grounding as a model) sends it straight into the
 * intake, like a regex parser, marked `parsedBy = template:<id>`. Anything else goes on to [next] (the AI fallback,
 * or nowhere when AI is off). Approved templates keep working with AI turned off.
 */
class TemplateReader(
    private val templates: MessageTemplateRepository,
    private val intake: IngestEventService,
    private val next: UnrecognisedMessageSink
) : UnrecognisedMessageSink {

    override suspend fun offer(message: UnrecognisedMessage): FallbackOffer {
        if (!MessageGrounding.mentionsMoney(message.text)) return next.offer(message)
        val approved = templates.approved(message.userId, message.channel, message.bankCode)
        if (approved.isEmpty()) return next.offer(message)
        val receivedOn = message.receivedAt.atZone(message.zone).toLocalDate()
        val question = MessageQuestion.of(message.text, receivedOn)
        for (t in approved) {
            val answer = TemplateReading.read(t, question) ?: continue
            val event = IngestEvent(
                userId = message.userId,
                source = message.channel.source,
                sourceRef = message.sourceRef,
                occurredAt = answer.occurredAt(message.receivedAt, receivedOn, message.zone),
                direction = answer.direction,
                amount = answer.amount,
                merchantRaw = answer.merchant,
                accountHint = answer.accountLast4,
                metadata = listOfNotNull(message.bankCode?.let { "bank" to it }, "parsedBy" to t.parsedBy).toMap()
            )
            val accepted = intake.ingest(listOf(event)).accepted
            templates.hit(t.id)
            return FallbackOffer.ReadByTemplate(duplicate = accepted == 0)
        }
        return next.offer(message)
    }
}
