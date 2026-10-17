package dev.adminos.api.domain.ingestion.sms

import dev.adminos.api.domain.ingestion.events.IngestEvent
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.events.IngestSource
import java.security.MessageDigest
import java.util.UUID

sealed interface SmsIntakeResult {
    data object Accepted : SmsIntakeResult
    /** A mandate notice, applied to the user's mandates (AOS-502). */
    data object Mandate : SmsIntakeResult
    /** A pay-later bill, now (or already) in the dues calendar (AOS-909). */
    data object Bill : SmsIntakeResult
    /** A travel booking, kept for trips (AOS-910). */
    data object Booking : SmsIntakeResult
    data object Duplicate : SmsIntakeResult
    data class Ignored(val reason: String) : SmsIntakeResult
}

/**
 * Raw SMS (from any transport) → parsed → common intake. The text exists only inside this call (D-33).
 * The same message always maps to the same `sourceRef`, so a resent SMS is a duplicate.
 */
class SmsIntake(
    private val parsers: SmsParserRegistry,
    private val intake: IngestEventService,
    private val mandates: dev.adminos.api.domain.financial.mandates.MandateService,
    private val payLaterBills: dev.adminos.api.domain.financial.paylater.PayLaterBills,
    private val travelBookings: dev.adminos.api.domain.financial.trips.TravelBookings
) {

    suspend fun accept(userId: UUID, message: SmsMessage): SmsIntakeResult =
        when (val result = parsers.parse(message)) {
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

    private fun fingerprint(m: SmsMessage): String {
        val header = SenderId.parse(m.sender)?.header ?: m.sender.uppercase()
        return MessageDigest.getInstance("SHA-256")
            .digest("sms-raw|$header|${m.text.replace(Regex("\\s+"), " ").trim()}".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
