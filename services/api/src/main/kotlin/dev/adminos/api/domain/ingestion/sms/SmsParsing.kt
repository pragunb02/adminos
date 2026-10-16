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
    val bankCode: String?
)

sealed interface SmsParseResult {
    data class Transaction(val parsed: ParsedSmsTransaction) : SmsParseResult
    /** Valid SMS that must not become a transaction. [reason] never quotes the text. */
    data class Ignored(val reason: String) : SmsParseResult
}

/** Parses one bank's (or the generic) SMS formats (Strategy). */
interface SmsParser {
    val bankCode: String?
    /** TRAI headers this parser owns (e.g. HDFCBK). Empty = generic fallback. */
    val headers: Set<String>
    /** @return null if this parser can't read the message (the registry then tries the fallback) */
    fun parse(text: String): ParsedSmsTransaction?
}
