package dev.adminos.api.domain.ingestion.fallback

import dev.adminos.api.domain.ai.AiReasonCode
import dev.adminos.api.domain.ingestion.events.IngestSource
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Where a message came from. Each is a source adapter in front of the same reader (research §6). */
enum class MessageChannel(val key: String, val source: IngestSource) {
    SMS("sms", IngestSource.SMS),
    WHATSAPP("whatsapp", IngestSource.WHATSAPP),
    GMAIL("gmail", IngestSource.GMAIL);

    companion object {
        fun fromKey(key: String): MessageChannel? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A bank message the regex parsers did not recognise, from a sender already known to be a bank (a TRAI business
 * header, or a WhatsApp bank name). Lives in memory only, for one call (D-33): [text] is never stored or logged,
 * and [toString] is redacted. [sourceRef] is the same fingerprint the regex path would have used, so a resent
 * message, or the same message once a parser learns it, is a duplicate.
 */
class UnrecognisedMessage(
    val userId: UUID,
    val channel: MessageChannel,
    val sourceRef: String,
    val receivedAt: Instant,
    val zone: ZoneId,
    /** The bank, when the sender says which (`hdfc`, …); null for an unknown bank. */
    val bankCode: String?,
    val text: String
) {
    init {
        require(sourceRef.isNotBlank() && sourceRef.length <= 255) { "sourceRef must be 1..255 characters" }
    }

    override fun toString() = "UnrecognisedMessage(channel=$channel, receivedAt=$receivedAt, text=<redacted ${text.length} chars>)"
}

/** What happened to a message offered to the fallback. Only [Queued] means a model will read it. */
sealed interface FallbackOffer {
    /** Kept in memory (redacted) until the reader's next run; its answer will wait in Review. */
    data object Queued : FallbackOffer

    /** Read by an approved template (AOS-706) and sent to the intake; [duplicate] when it was already there. */
    data class ReadByTemplate(val duplicate: Boolean) : FallbackOffer

    /** The feature is off on this server, or the user hasn't consented to it. */
    data object Off : FallbackOffer

    /** No money word: not worth a model call (offers, notices). */
    data object NoMoneyWord : FallbackOffer

    /** Already read once (a resent message): not asked again. */
    data object AlreadyRead : FallbackOffer

    /** The redactor refused to send it ([code]: `too_long`, `suspected_injection`, …). */
    data class Withheld(val code: AiReasonCode) : FallbackOffer

    /** Today's limit of messages to read is used up, or the in-memory queue is full (a burst): not read. */
    data object Busy : FallbackOffer
}

/** Where the regex intake sends what it can't read (Strategy). [NONE] is the server with the feature off. */
fun interface UnrecognisedMessageSink {
    suspend fun offer(message: UnrecognisedMessage): FallbackOffer

    companion object {
        val NONE = UnrecognisedMessageSink { FallbackOffer.Off }
    }
}

/**
 * May this user's messages be read by a model? Wired to the per-feature consent store (AOS-704,
 * `AiConsentCheck.allows(userId, AiFeature.MESSAGES)`, off by default); the gateway also checks it before every call.
 * [SERVER_FLAG] allows everyone and is for tests whose gateway decides consent itself.
 */
fun interface MessageReadingConsent {
    suspend fun allows(userId: UUID): Boolean

    companion object {
        val SERVER_FLAG = MessageReadingConsent { true }
    }
}
