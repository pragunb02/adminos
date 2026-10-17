package dev.adminos.api.domain.ask

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Thumbs up or down on one answer, kept to grow the eval set (AOS-711, research §7). It holds the **masked**
 * question and the plan the model made (tools and arguments, no amounts), never the answer's figures or any
 * transaction. The question is encrypted at rest by the persistence layer.
 */
data class AskFeedback(
    val askId: UUID,
    val userId: UUID,
    val helpful: Boolean,
    val question: String,
    /** The plan in the [AskPlanWire] shape, or null when the model's plan couldn't be read. */
    val planJson: String?,
    val source: AskSource,
    val promptVersion: String,
    val at: Instant
) {
    init {
        require(question.length <= AskService.MAX_QUESTION) { "A stored question is at most ${AskService.MAX_QUESTION} characters" }
        require(planJson == null || planJson.length <= AskPlanWire.MAX_TEXT) { "A stored plan is at most ${AskPlanWire.MAX_TEXT} characters" }
    }

    override fun toString() = "AskFeedback($askId, helpful=$helpful, source=${source.key})" // never print the question
}

interface AskFeedbackRepository {
    /** Saves [feedback], replacing an earlier vote on the same answer (a changed mind is one row). */
    suspend fun save(feedback: AskFeedback)

    /** [userId]'s feedback, newest first (for exporting to the eval set). */
    suspend fun forUser(userId: UUID): List<AskFeedback>
}

class InMemoryAskFeedbackRepository : AskFeedbackRepository {
    private val rows = ConcurrentHashMap<UUID, AskFeedback>()

    override suspend fun save(feedback: AskFeedback) {
        rows[feedback.askId] = feedback
    }

    override suspend fun forUser(userId: UUID): List<AskFeedback> = rows.values.filter { it.userId == userId }.sortedByDescending { it.at }
}

/**
 * The answers a user got in the last [ttl], so a thumbs up or down can refer to one by id without the browser
 * sending the question or the plan back (it can't make up an eval row). In memory and short-lived on purpose:
 * nothing about a question is stored unless the user votes on it.
 */
class RecentAsks(private val clock: Clock, private val ttl: Duration = TTL, private val perUser: Int = PER_USER) {
    data class Entry(val userId: UUID, val question: String, val planJson: String?, val source: AskSource, val at: Instant, val seq: Long) {
        override fun toString() = "RecentAsks.Entry(${source.key}, $at)"
    }

    private val entries = ConcurrentHashMap<UUID, Entry>()
    /** Orders answers given in the same instant. */
    private val seq = java.util.concurrent.atomic.AtomicLong()

    fun remember(userId: UUID, question: String, plan: AskPlan?, source: AskSource): UUID {
        val now = clock.instant()
        entries.values.removeIf { it.at.isBefore(now.minus(ttl)) }
        entries.filterValues { it.userId == userId }.entries.sortedBy { it.value.seq }.dropLast(perUser - 1).forEach { entries.remove(it.key) }
        val id = UUID.randomUUID()
        entries[id] = Entry(userId, question, plan?.let { AskPlanWire.encode(it).toString() }, source, now, seq.incrementAndGet())
        return id
    }

    /** The answer [askId] if it was [userId]'s and is still recent; another user's id is the same as an unknown one. */
    fun find(userId: UUID, askId: UUID): Entry? =
        entries[askId]?.takeIf { it.userId == userId && !it.at.isBefore(clock.instant().minus(ttl)) }

    companion object {
        val TTL: Duration = Duration.ofHours(1)
        const val PER_USER = 20
    }
}

/** Records a vote on a recent answer (AOS-711). */
class AskFeedbackService(private val recent: RecentAsks, private val repository: AskFeedbackRepository, private val clock: Clock) {
    sealed interface Result {
        data object Saved : Result

        /** Unknown, expired or someone else's answer. */
        data object NotFound : Result
    }

    suspend fun vote(userId: UUID, askId: UUID, helpful: Boolean): Result {
        val e = recent.find(userId, askId) ?: return Result.NotFound
        repository.save(AskFeedback(askId, userId, helpful, e.question, e.planJson, e.source, AskContract.PROMPT_VERSION, clock.instant()))
        return Result.Saved
    }
}
