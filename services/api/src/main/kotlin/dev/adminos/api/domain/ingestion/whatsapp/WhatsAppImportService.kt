package dev.adminos.api.domain.ingestion.whatsapp

import dev.adminos.api.domain.financial.mandates.MandateService
import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.domain.ingestion.events.IngestEvent
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.events.IngestSource
import dev.adminos.api.domain.ingestion.fallback.FallbackOffer
import dev.adminos.api.domain.ingestion.fallback.MessageChannel
import dev.adminos.api.domain.ingestion.fallback.UnrecognisedMessage
import dev.adminos.api.domain.ingestion.fallback.UnrecognisedMessageSink
import dev.adminos.api.domain.ingestion.sms.SmsParseResult
import dev.adminos.api.domain.ingestion.sms.SmsParserRegistry
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class WhatsAppImportResult(
    /** Every message read from the chat. */
    val messages: Int,
    /** Messages from senders recognised as banks; the rest are never parsed. */
    val fromBanks: Int,
    val accepted: Int,
    val duplicates: Int,
    /** Mandate notices applied to the user's mandates. */
    val mandates: Int,
    /** Unrecognised bank messages queued for the AI fallback; their answers wait in Review (AOS-705). */
    val reading: Int,
    /** Bank messages that aren't transactions, counted by reason (never by text). */
    val ignored: Map<String, Int>,
    /** Lines that looked like a message start but had an impossible date or time. */
    val unreadableLines: Int
)

/**
 * WhatsApp chat export → intake events (AOS-403, D-66). Bank alerts on WhatsApp read like their SMS, so each
 * bank message goes through the same [SmsParserRegistry] rules (OTP, failed, mandate, drop rules, bank parser).
 * The chat stays in memory (D-33). Each message's `sourceRef` is a fingerprint of sender, time and text, so
 * importing the same chat (or a longer export of it) again adds only new messages.
 */
class WhatsAppImportService(
    private val parsers: SmsParserRegistry,
    private val intake: IngestEventService,
    private val mandates: MandateService,
    private val calendar: UserCalendar,
    private val clock: Clock = Clock.systemUTC(),
    private val fallback: UnrecognisedMessageSink = UnrecognisedMessageSink.NONE
) {
    companion object {
        private const val CHUNK = 100 // IngestEventService.MAX_BATCH
        private val FUTURE_SLACK: Duration = Duration.ofMinutes(5)
        const val DATED_IN_FUTURE = "dated in the future (check the phone's date format)"
    }

    suspend fun import(userId: UUID, file: ByteArray): WhatsAppImportResult {
        val export = WhatsAppExportParser.parse(WhatsAppExportFile.text(file))
        val zone = calendar.zoneOf(userId)
        val latestAllowed = clock.instant().plus(FUTURE_SLACK)
        val ignored = sortedMapOf<String, Int>()
        val events = mutableListOf<IngestEvent>()
        var fromBanks = 0
        var mandateCount = 0
        var reading = 0
        var templateAccepted = 0
        var templateDuplicates = 0

        for (message in export.messages) {
            val bank = BankSenders.match(message.sender) ?: continue
            fromBanks++
            val at = message.at.atZone(zone).toInstant()
            if (at.isAfter(latestAllowed)) { ignored.merge(DATED_IN_FUTURE, 1, Int::plus); continue }
            val ref = fingerprint(message.sender, at, message.text)
            val bankCode = (bank as? BankSenders.Bank)?.code
            when (val result = parsers.parseFromBank(message.text, bankCode)) {
                SmsParseResult.Unrecognised -> {
                    when (val offer = fallback.offer(UnrecognisedMessage(userId, MessageChannel.WHATSAPP, ref, at, zone, bankCode, message.text))) {
                        FallbackOffer.Queued -> reading++
                        is FallbackOffer.ReadByTemplate -> if (offer.duplicate) templateDuplicates++ else templateAccepted++ // AOS-706
                        else -> ignored.merge(SmsParseResult.UNRECOGNISED, 1, Int::plus)
                    }
                }
                is SmsParseResult.Ignored -> ignored.merge(result.reason, 1, Int::plus)
                is SmsParseResult.Mandate -> { mandates.record(userId, result.notice, at, ref); mandateCount++ }
                is SmsParseResult.Transaction -> events += toEvent(userId, result, at, ref)
            }
        }

        val accepted = events.chunked(CHUNK).sumOf { intake.ingest(it).accepted } + templateAccepted
        return WhatsAppImportResult(
            messages = export.messages.size,
            fromBanks = fromBanks,
            accepted = accepted,
            duplicates = events.size + templateAccepted - accepted + templateDuplicates,
            mandates = mandateCount,
            reading = reading,
            ignored = ignored,
            unreadableLines = export.unreadableLines
        )
    }

    private fun toEvent(userId: UUID, result: SmsParseResult.Transaction, at: Instant, ref: String): IngestEvent {
        val p = result.parsed
        return IngestEvent(
            userId = userId,
            source = IngestSource.WHATSAPP,
            sourceRef = ref,
            occurredAt = at,
            direction = p.direction,
            amount = p.amount,
            merchantRaw = p.merchantRaw,
            accountHint = p.accountHint,
            paymentMethod = p.paymentMethod,
            upiVpa = p.upiVpa,
            paymentRef = p.reference,
            metadata = (listOfNotNull(p.bankCode?.let { "bank" to it }) + p.markers.toList()).toMap().takeIf { it.isNotEmpty() }
        )
    }

    private fun fingerprint(sender: String, at: Instant, text: String): String {
        val normalized = "whatsapp|${sender.trim().lowercase()}|$at|${text.replace(Regex("\\s+"), " ").trim()}"
        return MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
