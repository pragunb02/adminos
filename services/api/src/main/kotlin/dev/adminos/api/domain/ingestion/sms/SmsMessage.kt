package dev.adminos.api.domain.ingestion.sms

import java.time.Instant

/**
 * A received SMS, as delivered by a transport (iOS Shortcut, Mac reader, …).
 * Lives in memory only: [text] is never stored, logged or put in errors (D-33). [toString] is redacted.
 */
class SmsMessage(val sender: String, val text: String, val receivedAt: Instant) {
    override fun toString(): String = "SmsMessage(sender=$sender, receivedAt=$receivedAt, text=<redacted ${text.length} chars>)"
}

/**
 * Indian commercial SMS sender id per TRAI: `XY-HEADER-S`. The 2-letter prefix is the operator/circle
 * and varies, so match on [header]. The suffix is the category: S service, T transactional, P promotional,
 * G government.
 */
data class SenderId(val header: String, val category: Char?) {
    val isPromotional: Boolean get() = category == 'P'

    companion object {
        private val TRAI = Regex("^(?:[A-Z]{2}-)?([A-Z0-9]{3,9})(?:-([STPG]))?$")

        /** Null for personal numbers or unrecognised senders (banks can't send from those). */
        fun parse(raw: String): SenderId? {
            val s = raw.trim().uppercase()
            if (s.any { it.isDigit() } && s.count { it.isDigit() } >= 7) return null // phone number
            val m = TRAI.matchEntire(s) ?: return null
            return SenderId(m.groupValues[1], m.groupValues[2].firstOrNull())
        }
    }
}
