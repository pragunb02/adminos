package dev.adminos.api.domain.ingestion.sms

import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.domain.ingestion.events.IngestEvent
import dev.adminos.api.domain.ingestion.fallback.FallbackOffer
import dev.adminos.api.domain.ingestion.fallback.MessageChannel
import dev.adminos.api.domain.ingestion.fallback.UnrecognisedMessage
import dev.adminos.api.domain.ingestion.fallback.UnrecognisedMessageSink
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.events.IngestSource
import dev.adminos.api.domain.balances.BalanceObservation
import dev.adminos.api.domain.balances.BalanceRecordResult
import dev.adminos.api.domain.balances.BalanceService
import dev.adminos.api.domain.balances.BalanceSource
import java.security.MessageDigest
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** What one SMS did: its transaction (or why none), and whether it updated an account's balance (AOS-720). */
data class SmsIntakeOutcome(val result: SmsIntakeResult, val balanceNoted: Boolean)

sealed interface SmsIntakeResult {
    data object Accepted : SmsIntakeResult
    /** A mandate notice, applied to the user's mandates (AOS-502). */
    data object Mandate : SmsIntakeResult
    /** A pay-later bill, now (or already) in the dues calendar (AOS-909). */
    data object Bill : SmsIntakeResult
    /** A travel booking, kept for trips (AOS-910). */
    data object Booking : SmsIntakeResult
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
 * A trusted bank's balance figure is noted for its account (AOS-720, D-170), whatever happened to the transaction.
 */
class SmsIntake(
    private val parsers: SmsParserRegistry,
    private val intake: IngestEventService,
    private val mandates: dev.adminos.api.domain.financial.mandates.MandateService,
    private val payLaterBills: dev.adminos.api.domain.financial.paylater.PayLaterBills,
    private val travelBookings: dev.adminos.api.domain.financial.trips.TravelBookings,
    private val balances: BalanceService,
    private val fallback: UnrecognisedMessageSink = UnrecognisedMessageSink.NONE,
    /** The user's time zone, for the date a message arrived on (the fallback's date check). */
    private val zoneOf: suspend (UUID) -> ZoneId = { UserCalendar.DEFAULT_ZONE }
) {
    private val balanceGate = SmsBalanceGate(parsers)

    suspend fun accept(userId: UUID, message: SmsMessage): SmsIntakeResult = receive(userId, message).result

    suspend fun receive(userId: UUID, message: SmsMessage): SmsIntakeOutcome {
        val parsed = parsers.parse(message)
        val result = transaction(userId, message, parsed)
        return SmsIntakeOutcome(result, noteBalance(userId, message, parsed))
    }

    private suspend fun noteBalance(userId: UUID, message: SmsMessage, parsed: SmsParseResult): Boolean {
        val capture = balanceGate.read(message, parsed) as? SmsBalanceCapture.Trusted ?: return false
        val b = capture.balance
        // The balance is as of the SMS, unless the bank dates it earlier: "as on 15-10-26 09:30" at that time, a bare
        // "as on 15-10-26" at the end of that day. A date that isn't before the SMS can't be checked, so the SMS's time wins.
        val observedAt = b.asOn?.let { day -> day.atTime(b.asOnTime ?: END_OF_DAY).atZone(IST).toInstant() }
            ?.takeIf { it.isBefore(message.receivedAt) } ?: message.receivedAt
        val r = balances.record(BalanceObservation(userId, capture.bankCode, b.accountHint, b.amount, observedAt, BalanceSource.SMS, fingerprint(message)))
        return r == BalanceRecordResult.Saved
    }

    private suspend fun transaction(userId: UUID, message: SmsMessage, parsed: SmsParseResult): SmsIntakeResult =
        when (val result = parsed) {
            SmsParseResult.Unrecognised -> offer(userId, message)
            is SmsParseResult.Ignored -> SmsIntakeResult.Ignored(result.reason)
            is SmsParseResult.Mandate -> {
                mandates.record(userId, result.notice, message.receivedAt, fingerprint(message))
                SmsIntakeResult.Mandate
            }
            is SmsParseResult.PayLaterBill -> {
                payLaterBills.record(userId, result.notice, message.receivedAt, fingerprint(message), source = "sms")
                SmsIntakeResult.Bill
            }
            is SmsParseResult.TravelBooking -> {
                travelBookings.record(userId, result.notice, message.receivedAt, fingerprint(message))
                SmsIntakeResult.Booking
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
        return when (val offer = fallback.offer(unrecognised)) {
            FallbackOffer.Queued -> SmsIntakeResult.Reading
            is FallbackOffer.ReadByTemplate -> if (offer.duplicate) SmsIntakeResult.Duplicate else SmsIntakeResult.Accepted
            else -> SmsIntakeResult.Ignored(SmsParseResult.UNRECOGNISED)
        }
    }

    private companion object {
        /** Indian banks' SMS dates are India time (as the record adapter assumes). */
        val IST: ZoneId = ZoneId.of("Asia/Kolkata")
        val END_OF_DAY: LocalTime = LocalTime.of(23, 59, 59)
    }

    private fun fingerprint(m: SmsMessage): String {
        val header = SenderId.parse(m.sender)?.header ?: m.sender.uppercase()
        return MessageDigest.getInstance("SHA-256")
            .digest("sms-raw|$header|${m.text.replace(Regex("\\s+"), " ").trim()}".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
