package dev.adminos.api.domain.ingestion.sms

import dev.adminos.api.domain.ingestion.events.IngestEvent
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.events.IngestSource
import java.security.MessageDigest
import java.util.UUID

sealed interface SmsIntakeResult {
    data object Accepted : SmsIntakeResult
    data object Duplicate : SmsIntakeResult
    data class Ignored(val reason: String) : SmsIntakeResult
}

/**
 * Raw SMS (from any transport) → parsed → common intake. The text exists only inside this call (D-33).
 * The same message always maps to the same `sourceRef`, so a resent SMS is a duplicate.
 */
class SmsIntake(private val parsers: SmsParserRegistry, private val intake: IngestEventService) {

    suspend fun accept(userId: UUID, message: SmsMessage): SmsIntakeResult =
        when (val result = parsers.parse(message)) {
            is SmsParseResult.Ignored -> SmsIntakeResult.Ignored(result.reason)
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
                    metadata = p.bankCode?.let { mapOf("bank" to it) }
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
