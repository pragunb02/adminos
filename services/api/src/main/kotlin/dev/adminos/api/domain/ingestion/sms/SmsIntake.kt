package dev.adminos.api.domain.ingestion.sms

import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.domain.ingestion.events.IngestEvent
import dev.adminos.api.domain.ingestion.fallback.FallbackOffer
import dev.adminos.api.domain.ingestion.fallback.MessageChannel
import dev.adminos.api.domain.ingestion.fallback.UnrecognisedMessage
import dev.adminos.api.domain.ingestion.fallback.UnrecognisedMessageSink
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.events.IngestSource
import java.security.MessageDigest
import java.time.ZoneId
import java.util.UUID

sealed interface SmsIntakeResult {
    data object Accepted : SmsIntakeResult
    /** A mandate notice, applied to the user's mandates (AOS-502). */
    data object Mandate : SmsIntakeResult
    data object Duplicate : SmsIntakeResult
    /** No parser read it; queued for the AI fallback, whose answer will wait in Review (AOS-705). */
    data object Reading : SmsIntakeResult
    data class Ignored(val reason: String) : SmsIntakeResult
}

/**
 * Raw SMS (from any transport) → parsed → common intake. The text exists only inside this call (D-33).
 * The same message always maps to the same `sourceRef`, so a resent SMS is a duplicate.
 * A message from a bank header that no parser recognises is offered to [fallback] (AOS-705); every other
 * ignored message (OTP, promotion, reminder, personal sender) never is.
 */
class SmsIntake(
    private val parsers: SmsParserRegistry,
    private val intake: IngestEventService,
    private val mandates: dev.adminos.api.domain.financial.mandates.MandateService,
    private val fallback: UnrecognisedMessageSink = UnrecognisedMessageSink.NONE,
    /** The user's time zone, for the date a message arrived on (the fallback's date check). */
    private val zoneOf: suspend (UUID) -> ZoneId = { UserCalendar.DEFAULT_ZONE }
) {

    suspend fun accept(userId: UUID, message: SmsMessage): SmsIntakeResult =
        when (val result = parsers.parse(message)) {
            SmsParseResult.Unrecognised -> offer(userId, message)
            is SmsParseResult.Ignored -> SmsIntakeResult.Ignored(result.reason)
            is SmsParseResult.Mandate -> {
                mandates.record(userId, result.notice, message.receivedAt, fingerprint(message))
                SmsIntakeResult.Mandate
            }
            is SmsParseResult.Transaction -> {
                val p = result.parsed
                val event = IngestEvent(
                    userId = userId,
                    source = IngestSource.SMS,
                    sourceRef = fingerprint(message),
                    occurredAt = message.receivedAt,
                    direction = p.direction,
                    amount = p.amount,
                    merchantRaw = p.merchantRaw,
                    accountHint = p.accountHint,
                    paymentMethod = p.paymentMethod,
                    upiVpa = p.upiVpa,
                    paymentRef = p.reference,
                    metadata = (listOfNotNull(p.bankCode?.let { "bank" to it }) + p.markers.toList()).toMap().takeIf { it.isNotEmpty() }
                )
                if (intake.ingest(listOf(event)).accepted == 1) SmsIntakeResult.Accepted else SmsIntakeResult.Duplicate
            }
        }

    private suspend fun offer(userId: UUID, message: SmsMessage): SmsIntakeResult {
        val sender = SenderId.parse(message.sender)
        val unrecognised = UnrecognisedMessage(
            userId = userId, channel = MessageChannel.SMS, sourceRef = fingerprint(message), receivedAt = message.receivedAt,
            zone = zoneOf(userId), bankCode = sender?.let { parsers.bankFor(it.header) }, text = message.text
        )
        return when (fallback.offer(unrecognised)) {
            FallbackOffer.Queued -> SmsIntakeResult.Reading
            else -> SmsIntakeResult.Ignored(SmsParseResult.UNRECOGNISED)
        }
    }

    private fun fingerprint(m: SmsMessage): String {
        val header = SenderId.parse(m.sender)?.header ?: m.sender.uppercase()
        return MessageDigest.getInstance("SHA-256")
            .digest("sms-raw|$header|${m.text.replace(Regex("\\s+"), " ").trim()}".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
