package dev.adminos.api.domain.ingestion.sms

/** What an SMS gives the balances (AOS-720). */
sealed interface SmsBalanceCapture {
    /** A balance from a bank sender we trust, with that bank. */
    data class Trusted(val bankCode: String, val balance: SmsBalance) : SmsBalanceCapture {
        override fun toString() = "Trusted($bankCode)"
    }

    data class Skipped(val reason: NoBalance) : SmsBalanceCapture
}

/**
 * Which SMS may give a balance (AOS-720, D-170), before [SmsBalanceReader] reads one:
 * - the sender is a registered, non-promotional header that one of our bank parsers owns (a generic or unknown
 *   header could be anyone, and a fake figure would mislead "money left");
 * - the message is a transaction we read (or one whose direction is unclear: a transfer between own accounts still
 *   quotes one balance), a pure balance alert, or one that names no transaction at all ("Bal for A/c XX1234 is…");
 *   never an OTP, a failure, a promise, a request, a notice or a mandate;
 * - the balance phrase isn't inside the merchant's name ("AVL BAL RS 99999" as a shop name).
 */
class SmsBalanceGate(private val registry: SmsParserRegistry) {

    private companion object {
        val READABLE = setOf(SmsParseResult.BALANCE_ALERT, SmsParseResult.NOT_RECOGNISED, SmsParseResult.DIRECTION_UNCLEAR)
    }

    fun read(message: SmsMessage, parsed: SmsParseResult): SmsBalanceCapture {
        val bank = registry.knownBankOf(message.sender) ?: return SmsBalanceCapture.Skipped(NoBalance.UNTRUSTED_SENDER)
        val readable = when (parsed) {
            is SmsParseResult.Transaction -> if (SmsBalanceReader.inMerchant(parsed.parsed.merchantRaw)) NoBalance.IN_MERCHANT else null
            is SmsParseResult.Ignored -> if (parsed.reason in READABLE) null else NoBalance.NOT_READ
            is SmsParseResult.Mandate -> NoBalance.NOT_READ
        }
        if (readable != null) return SmsBalanceCapture.Skipped(readable)
        return when (val r = SmsBalanceReader.read(message.text)) {
            is SmsBalanceResult.Found -> SmsBalanceCapture.Trusted(bank, r.balance)
            is SmsBalanceResult.NotFound -> SmsBalanceCapture.Skipped(r.reason)
        }
    }
}
