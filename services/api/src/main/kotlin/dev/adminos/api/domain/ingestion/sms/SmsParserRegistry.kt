package dev.adminos.api.domain.ingestion.sms

/**
 * Picks the parser for a sender (Registry) and applies the drop rules first. New bank = new entry
 * in [DEFAULT_PARSERS], no edits elsewhere.
 */
class SmsParserRegistry(parsers: List<SmsParser> = DEFAULT_PARSERS) {

    private val byHeader: Map<String, SmsParser> = parsers.flatMap { p -> p.headers.map { it to p } }.toMap()
    private val byBank: Map<String, SmsParser> = parsers.mapNotNull { p -> p.bankCode?.let { it to p } }.toMap()
    private val generic: SmsParser = parsers.firstOrNull { it.headers.isEmpty() } ?: GENERIC

    fun parse(message: SmsMessage): SmsParseResult {
        val sender = SenderId.parse(message.sender)
            ?: return SmsParseResult.Ignored("sender is not a registered business header")
        if (sender.isPromotional) return SmsParseResult.Ignored("promotional sender")
        return parseText(message.text, byHeader[sender.header])
    }

    /**
     * For channels without TRAI headers (a WhatsApp export, AOS-403): the caller has already decided the
     * text comes from a bank, and names it by [bankCode] when it knows which one (null = generic parser).
     */
    fun parseFromBank(text: String, bankCode: String?): SmsParseResult = parseText(text, bankCode?.let { byBank[it] })

    private fun parseText(text: String, parser: SmsParser?): SmsParseResult {
        // OTPs and failures are never anything; mandate notices come before the "upcoming payment" drop rule,
        // because a pre-debit notice is exactly that (AOS-502).
        BEFORE_MANDATES.firstOrNull { (re, _) -> re.containsMatchIn(text) }?.let { return SmsParseResult.Ignored(it.second) }
        MandateSmsParser.parse(text, parser?.bankCode)?.let { return SmsParseResult.Mandate(it) }
        DROP_RULES.firstOrNull { (re, _) -> re.containsMatchIn(text) }?.let { return SmsParseResult.Ignored(it.second) }

        val parsed = parser?.parse(text) ?: generic.parse(text)
            ?: return SmsParseResult.Ignored("not a recognised transaction message")
        return SmsParseResult.Transaction(parsed.copy(markers = MandateDebitMarker.metadataFor(text)))
    }

    companion object {
        private val I = RegexOption.IGNORE_CASE

        /** Never anything, not even a mandate notice. */
        private val BEFORE_MANDATES: List<Pair<Regex, String>> = listOf(
            Regex("\\b(OTP|one[ -]time password|verification code)\\b", I) to "one-time password",
            Regex("\\b(declined|failed|unsuccessful|could not be processed)\\b", I) to "failed or declined transaction"
        )

        /** Checked before parsing transactions. Order matters only for the reason reported. */
        private val DROP_RULES: List<Pair<Regex, String>> = listOf(
            Regex("\\b(collect request|has requested (money|Rs|INR)|requested money)\\b", I) to "payment request, not a payment",
            Regex("\\b(will be debited|is due|due on|auto-?debit (is )?scheduled|reminder)\\b", I) to "upcoming payment notice",
            Regex("^(?!.*\\b(debited|credited|spent|sent|paid|received)\\b).*\\b(available balance|avl bal)\\b", setOf(I, RegexOption.DOT_MATCHES_ALL)) to "balance alert"
        )

        val GENERIC = BankSmsParser(bankCode = null, headers = emptySet(), merchantPatterns = emptyList())

        val DEFAULT_PARSERS: List<SmsParser> = listOf(
            BankSmsParser("hdfc", setOf("HDFCBK", "HDFCBN"), emptyList()),
            BankSmsParser("sbi", setOf("SBIUPI", "SBIINB", "SBMSMS", "ATMSBI", "SBIPSG", "CBSSBI"), emptyList()),
            BankSmsParser("icici", setOf("ICICIB", "ICICIT"), emptyList()),
            BankSmsParser("axis", setOf("AXISBK", "AXISMR"), emptyList()),
            BankSmsParser("kotak", setOf("KOTAKB", "KOTAKM"), emptyList()),
            GENERIC
        )
    }
}
