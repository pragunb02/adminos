package dev.adminos.api.domain.ingestion.fallback

import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.domain.ai.AiGateway
import dev.adminos.api.domain.ai.AiResult
import dev.adminos.api.domain.ingestion.fallback.templates.MessageShapes
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/** What one [MessageFallback.drain] did, as counts (never amounts or text). */
data class DrainCounts(
    val proposed: Int = 0,
    val notTransactions: Int = 0,
    val duplicates: Int = 0,
    val rejected: Int = 0,
    val retried: Int = 0,
    val dropped: Int = 0,
    /** Items whose handling threw (e.g. the database was down); logged by id, not read again. */
    val failed: Int = 0
) {
    operator fun plus(o: DrainCounts) = DrainCounts(proposed + o.proposed, notTransactions + o.notTransactions,
        duplicates + o.duplicates, rejected + o.rejected, retried + o.retried, dropped + o.dropped, failed + o.failed)

    fun asMap(): Map<String, Int> = mapOf("proposed" to proposed, "notTransactions" to notTransactions, "duplicates" to duplicates,
        "rejected" to rejected, "retried" to retried, "dropped" to dropped, "failed" to failed)
}

/**
 * The message-reading fallback (AOS-705, D-106): the regex intake [offer]s what it can't read, and a background
 * job [drain]s it through [AiGateway] with [ReadMessageTask], so a slow local model never slows an SMS upload.
 *
 * **Before anything is kept**, a message must: have the feature on and the user's consent, mention money, not have
 * been read before, and pass [MessageRedactor]. Only the **redacted** text is kept, in this process's memory, and
 * only until its run (at most [capacity] messages; lost on restart, which is safe: nothing was promised).
 * At most [dailyLimit] messages a day (UTC) are queued, so a burst (a backfill, spam) can't run the model all day;
 * cloud spend is also capped by the gateway's monthly cap.
 *
 * **An accepted answer** becomes a [MessageProposal] in Review, never an intake event: it reaches the totals
 * only when the user confirms it ([MessageReviewService]). A rejected answer, a "not a transaction" answer and a
 * refusal keep nothing. An unavailable model is retried on the next runs, up to [MAX_ATTEMPTS] in all.
 */
class MessageFallback(
    private val gateway: AiGateway,
    private val proposals: MessageProposalRepository,
    private val consent: MessageReadingConsent = MessageReadingConsent.SERVER_FLAG,
    private val clock: Clock = Clock.systemUTC(),
    private val capacity: Int = DEFAULT_CAPACITY,
    private val dailyLimit: Int = DEFAULT_DAILY_LIMIT
) : UnrecognisedMessageSink {

    init {
        require(capacity in 1..MAX_CAPACITY) { "capacity is 1..$MAX_CAPACITY" }
        require(dailyLimit in 0..MAX_DAILY_LIMIT) { "dailyLimit is 0..$MAX_DAILY_LIMIT" }
    }

    /** One queued message: who, where it goes, and the redacted question. No raw text. */
    private class Item(
        val userId: UUID,
        val channel: MessageChannel,
        val sourceRef: String,
        val receivedAt: Instant,
        val zone: ZoneId,
        val bankCode: String?,
        val question: MessageQuestion,
        val attempts: Int = 0
    ) {
        fun retried() = Item(userId, channel, sourceRef, receivedAt, zone, bankCode, question, attempts + 1)
    }

    private val logger = LoggerFactory.getLogger(MessageFallback::class.java)
    private val queue = ConcurrentLinkedQueue<Item>()
    private val size = AtomicInteger(0)
    private val today = java.util.concurrent.atomic.AtomicReference(Day(LocalDate.MIN, 0))

    private data class Day(val date: LocalDate, val queued: Int)

    /** How many messages wait for the next run. */
    val queued: Int get() = size.get()

    override suspend fun offer(message: UnrecognisedMessage): FallbackOffer {
        if (gateway.route(AiFeature.MESSAGES) == null || !consent.allows(message.userId)) return FallbackOffer.Off
        if (!MessageGrounding.mentionsMoney(message.text)) return FallbackOffer.NoMoneyWord
        val question = MessageQuestion.of(message.text, message.receivedAt.atZone(message.zone).toLocalDate())
        (question.redacted as? RedactedMessage.Withheld)?.let { return FallbackOffer.Withheld(it.code) }
        if (proposals.exists(message.userId, message.channel, message.sourceRef)) return FallbackOffer.AlreadyRead
        if (queue.any { it.userId == message.userId && it.channel == message.channel && it.sourceRef == message.sourceRef }) return FallbackOffer.AlreadyRead
        if (size.incrementAndGet() > capacity) {
            size.decrementAndGet()
            return FallbackOffer.Busy
        }
        if (!takeToday()) {
            size.decrementAndGet()
            return FallbackOffer.Busy
        }
        queue.add(Item(message.userId, message.channel, message.sourceRef, message.receivedAt, message.zone, message.bankCode, question))
        return FallbackOffer.Queued
    }

    /** Counts one message against today's limit, or false when it is used up. */
    private fun takeToday(): Boolean {
        val date = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        while (true) {
            val current = today.get()
            val day = if (current.date == date) current else Day(date, 0)
            if (day.queued >= dailyLimit) return false
            if (today.compareAndSet(current, day.copy(queued = day.queued + 1))) return true
        }
    }

    /**
     * Reads up to [max] queued messages, stopping early once [budget] has passed (a slow local model), so one run
     * can't hold the scheduler. What is left waits for the next run.
     */
    suspend fun drain(max: Int, budget: Duration): DrainCounts {
        val deadline = clock.instant().plus(budget)
        var counts = DrainCounts()
        val retry = mutableListOf<Item>()
        var done = 0
        while (done < max && clock.instant().isBefore(deadline)) {
            val item = queue.poll() ?: break
            size.decrementAndGet()
            done++
            counts += try {
                read(item, retry)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Message reading failed for user {} ({})", item.userId, e.javaClass.simpleName) // no text, no message
                DrainCounts(failed = 1)
            }
        }
        retry.forEach { queue.add(it); size.incrementAndGet() }
        return counts
    }

    private suspend fun read(item: Item, retry: MutableList<Item>): DrainCounts =
        when (val result = gateway.run(ReadMessageTask, item.userId, item.question)) {
            is AiResult.Answered -> when (val answer = result.value) {
                MessageAnswer.NotATransaction -> DrainCounts(notTransactions = 1)
                is MessageAnswer.Transaction ->
                    if (proposals.saveNew(proposal(item, answer, result.model))) DrainCounts(proposed = 1) else DrainCounts(duplicates = 1)
            }
            is AiResult.Rejected -> DrainCounts(rejected = 1)
            is AiResult.Unavailable ->
                if (item.attempts + 1 < MAX_ATTEMPTS) { retry += item.retried(); DrainCounts(retried = 1) } else DrainCounts(dropped = 1)
            is AiResult.Refused -> DrainCounts(dropped = 1) // turned off, no consent (AOS-704), withheld or over the cap: not read
        }

    private fun proposal(item: Item, answer: MessageAnswer.Transaction, model: String) = MessageProposal(
        userId = item.userId,
        channel = item.channel,
        sourceRef = item.sourceRef,
        occurredAt = answer.occurredAt(item.receivedAt, item.question.receivedOn, item.zone),
        direction = answer.direction,
        amount = answer.amount,
        accountLast4 = answer.accountLast4,
        merchant = answer.merchant,
        bankCode = item.bankCode,
        model = model,
        promptVersion = ReadMessageTask.promptVersion,
        createdAt = clock.instant(),
        // The masked shape, for template learning once the user confirms it (AOS-706); never the text.
        shape = (item.question.redacted as? RedactedMessage.Ready)?.let { MessageShapes.of(it.text, answer) }
    )

    companion object {
        const val DEFAULT_CAPACITY = 200
        const val MAX_CAPACITY = 10_000
        const val DEFAULT_DAILY_LIMIT = 100
        const val MAX_DAILY_LIMIT = 10_000
        /** An unavailable model is asked at most this many times about one message. */
        const val MAX_ATTEMPTS = 3
    }
}
