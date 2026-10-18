package dev.adminos.api.domain.ingestion.fallback.templates

import dev.adminos.api.domain.ai.AiReading
import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.fallback.MessageAnswer
import dev.adminos.api.domain.ingestion.fallback.MessageChannel
import dev.adminos.api.domain.ingestion.fallback.MessageGrounding
import dev.adminos.api.domain.ingestion.fallback.MessageQuestion
import dev.adminos.api.domain.ingestion.fallback.RedactedMessage
import dev.adminos.api.domain.ingestion.fallback.ReadMessageTask
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.UUID

/**
 * - OBSERVING: seen in confirmed readings, fewer than [TemplateLearning.MIN_SEEN] times;
 * - PROPOSED: seen often enough; waits in Review for the user;
 * - APPROVED: read by plain code before any model, straight into the intake;
 * - REJECTED: the user said no; kept so it is never proposed again.
 */
enum class TemplateStatus { OBSERVING, PROPOSED, APPROVED, REJECTED }

/**
 * A message format learnt from confirmed AI readings (AOS-706, D-107): one bank's wording for one direction, as a
 * [MessageShape]. Once the user approves it, it reads matching messages itself, before (and without) a model.
 */
data class MessageTemplate(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val channel: MessageChannel,
    /** The sender's bank (`hdfc`), or null for a bank no parser knows; a template only reads its own sender's messages. */
    val bankCode: String?,
    val direction: IngestDirection,
    val shape: MessageShape,
    val status: TemplateStatus = TemplateStatus.OBSERVING,
    /** Confirmed readings of this format. */
    val seen: Int = 1,
    /** Messages it has read since it was approved. */
    val hits: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt
) {
    init {
        require(seen >= 1 && hits >= 0) { "Counts are positive" }
    }

    /** The provenance written on an intake event this template read. */
    val parsedBy: String get() = "template:$id"

    override fun toString() = "MessageTemplate(id=$id, channel=$channel, status=$status, seen=$seen)"
}

/**
 * Reads a message with a template's shape: the shape compiles to a regex (wording literal, slots as groups), and what
 * the groups capture is checked by **the same validator as a model's answer** ([ReadMessageTask.read]): grounding,
 * direction words, ambiguity, the date window. A template can't accept anything a model couldn't.
 */
object TemplateReading {
    private const val NUMBER = "[0-9][0-9,]*(?:\\.[0-9]{1,2})?"

    /**
     * The regex for [shape]: anchored, whitespace-tolerant, case-insensitive. The first of each slot is captured.
     * Open-ended slots are bounded (`.{2,60}?`, `.{6,20}?`), a repeated payee is a back-reference, and a template
     * has at most [MAX_OPEN_SLOTS] of them ([isReadable]), so matching a 1,000-character message can't backtrack badly.
     */
    fun regex(shape: MessageShape): Regex {
        val seen = mutableSetOf<String>()
        val body = shape.tokens.joinToString("\\s+") { token ->
            val out = StringBuilder()
            var at = 0
            for (m in MessageShape.SLOT.findAll(token)) {
                if (m.range.first > at) out.append(Regex.escape(token.substring(at, m.range.first)))
                out.append(slot(m.value, first = seen.add(m.value)))
                at = m.range.last + 1
            }
            if (at < token.length) out.append(Regex.escape(token.substring(at)))
            out.toString()
        }
        return Regex("^$body$", RegexOption.IGNORE_CASE)
    }

    /** At most this many open-ended slots (the payee, dates) in a template. */
    const val MAX_OPEN_SLOTS = 3

    /** Whether [shape] may become a template: it has the amount, and few open-ended slots. */
    fun isReadable(shape: MessageShape): Boolean {
        val slots = MessageShape.SLOT.findAll(shape.text).map { it.value }.toList()
        val open = slots.count { it == MessageShape.DATE } + if (MessageShape.M in slots) 1 else 0
        return MessageShape.AMT in slots && open <= MAX_OPEN_SLOTS
    }

    private fun slot(slot: String, first: Boolean): String = when (slot) {
        MessageShape.AMT -> if (first) "(?<amt>$NUMBER)" else NUMBER
        MessageShape.BAL -> NUMBER
        MessageShape.ACCT -> if (first) "(?<acct>[0-9]{4})" else "[0-9]{4}"
        MessageShape.DATE -> if (first) "(?<date>.{6,20}?)" else ".{6,20}?"
        MessageShape.M -> if (first) "(?<m>.{2,60}?)" else "\\k<m>" // the same payee again: no second open-ended group
        MessageShape.N -> "[0-9]+"
        MessageShape.NAME -> "[A-Za-z][A-Za-z.]*"
        else -> "\\S+" // ANY
    }

    /** What [template] reads from [question], validated; null when it doesn't match or its reading isn't grounded. */
    fun read(template: MessageTemplate, question: MessageQuestion): MessageAnswer.Transaction? {
        val text = (question.redacted as? RedactedMessage.Ready)?.text ?: return null
        val m = regex(template.shape).matchEntire(text) ?: return null
        fun group(name: String) = runCatching { m.groups[name]?.value }.getOrNull()
        val date = group("date")?.let { raw -> MessageGrounding.dateSpans(raw).singleOrNull()?.takeIf { it.range == raw.indices }?.date ?: return null }
        val answer = JsonObject(mapOf(
            "kind" to JsonPrimitive("transaction"),
            "direction" to JsonPrimitive(template.direction.name.lowercase()),
            "amount" to (group("amt")?.replace(",", "")?.let(::JsonPrimitive) ?: return null),
            "accountLast4" to (group("acct")?.let(::JsonPrimitive) ?: JsonNull),
            "merchant" to (group("m")?.trim()?.let(::JsonPrimitive) ?: JsonNull),
            "date" to (date?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
        )).toString()
        return (ReadMessageTask.read(answer, question) as? AiReading.Valid)?.value as? MessageAnswer.Transaction
    }
}
