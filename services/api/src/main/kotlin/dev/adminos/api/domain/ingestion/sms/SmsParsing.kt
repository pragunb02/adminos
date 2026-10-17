package dev.adminos.api.domain.ingestion.sms

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.ingestion.events.IngestDirection
import java.math.BigDecimal

/** What a parser extracted from a financial SMS. Only these fields survive; the text does not (D-33). */
data class ParsedSmsTransaction(
    val direction: IngestDirection,
    val amount: BigDecimal,
    val accountHint: String?,
    val merchantRaw: String?,
    val upiVpa: String?,
    val paymentMethod: PaymentMethod?,
    val bankCode: String?,
    /** UPI/IMPS reference number (RRN), when the SMS shows one. */
    val reference: String? = null,
    /** Set when the debit pays a mandate: "mandate" (and "umn" when shown), AOS-502. */
    val markers: Map<String, String> = emptyMap()
)

sealed interface SmsParseResult {
    data class Transaction(val parsed: ParsedSmsTransaction) : SmsParseResult
    /** About a mandate (set up, pre-debit, paused, resumed, revoked), not a transaction (AOS-502). */
    data class Mandate(val notice: dev.adminos.api.domain.financial.mandates.MandateNotice) : SmsParseResult
    /** Valid SMS that must not become a transaction. [reason] never quotes the text. */
    data class Ignored(val reason: String) : SmsParseResult

    companion object {
        /** Debit and credit signals disagree: skipped and counted, never guessed (D-43, AOS-215). */
        const val DIRECTION_UNCLEAR = "direction unclear, needs review"
        /** "Rs 50 cashback will be credited…": a promise, not money that moved (AOS-213). */
        const val PROMISED_CREDIT = "promised credit, not a transaction"
    }
}

/** What one parser made of a message. */
sealed interface SmsReading {
    data class Parsed(val transaction: ParsedSmsTransaction) : SmsReading
    /** A transaction whose direction can't be told (its signals conflict); never guessed. */
    data object DirectionUnclear : SmsReading
    data object NotATransaction : SmsReading
}

/** Parses one bank's (or the generic) SMS formats (Strategy). */
interface SmsParser {
    val bankCode: String?
    /** TRAI headers this parser owns (e.g. HDFCBK). Empty = generic fallback. */
    val headers: Set<String>
    /** @return null if this parser can't read the message (the registry then tries the fallback) */
    fun parse(text: String): ParsedSmsTransaction?

    /** Like [parse], but also says when a transaction was found whose direction is unclear. */
    fun read(text: String): SmsReading = parse(text)?.let(SmsReading::Parsed) ?: SmsReading.NotATransaction
}
