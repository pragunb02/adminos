package dev.adminos.api.domain.ingestion.sms

/**
 * Picks the parser for a sender (Registry) and applies the drop rules first. New bank = new entry
 * in [DEFAULT_PARSERS], no edits elsewhere.
 */
class SmsParserRegistry(parsers: List<SmsParser> = DEFAULT_PARSERS) {

    private val byHeader: Map<String, SmsParser> = parsers.flatMap { p -> p.headers.map { it to p } }.toMap()
    private val byBank: Map<String, SmsParser> = parsers.mapNotNull { p -> p.bankCode?.let { it to p } }.toMap()
    private val generic: SmsParser = parsers.firstOrNull { it.headers.isEmpty() } ?: GENERIC

    /** The bank that owns a TRAI [header] (`hdfc` for HDFCBK), or null when no bank parser claims it. */
    fun bankFor(header: String): String? = byHeader[header.uppercase()]?.bankCode

    fun parse(message: SmsMessage): SmsParseResult {
        val sender = SenderId.parse(message.sender)
            ?: return SmsParseResult.Ignored("sender is not a registered business header")
        if (sender.isPromotional) return SmsParseResult.Ignored("promotional sender")
        neverFinancial(message.text)?.let { return SmsParseResult.Ignored(it) }
        // Pay-later providers (AOS-909) before the drop rules, which would drop their "bill is due" texts.
        payLater(message)?.let { return it }
        TravelBookingSmsParser.parse(message.text, message.receivedAt.atZone(INDIA).toLocalDate())?.let { return SmsParseResult.TravelBooking(it) }
        return parseText(message.text, byHeader[sender.header])
    }

    private fun payLater(message: SmsMessage): SmsParseResult? =
        when (val m = PayLaterSmsParser.parse(message.text, message.receivedAt.atZone(INDIA).toLocalDate())) {
            null -> null
            is PayLaterMessage.Bill -> SmsParseResult.PayLaterBill(m.notice)
            is PayLaterMessage.Purchase -> SmsParseResult.Transaction(m.transaction)
            PayLaterMessage.Repayment -> SmsParseResult.Ignored("pay-later repayment confirmation; the bank alert records the payment")
            is PayLaterMessage.Unreadable -> SmsParseResult.Ignored(m.reason)
        }

    private fun neverFinancial(text: String): String? = BEFORE_MANDATES.firstOrNull { (re, _) -> re.containsMatchIn(text) }?.second

    /**
     * The bank whose own parser owns [sender]'s header, or null for promotional, personal, unknown or generic senders.
     * Only these senders' balances are trusted (AOS-720, D-170): a header we don't know could be anyone's.
     */
    fun knownBankOf(sender: String): String? =
        SenderId.parse(sender)?.takeUnless { it.isPromotional }?.let { bankFor(it.header) }

    /**
     * For channels without TRAI headers (a WhatsApp export, AOS-403): the caller has already decided the
     * text comes from a bank, and names it by [bankCode] when it knows which one (null = generic parser).
     */
    fun parseFromBank(text: String, bankCode: String?): SmsParseResult = parseText(text, bankCode?.let { byBank[it] })

    private fun parseText(text: String, parser: SmsParser?): SmsParseResult {
        // OTPs and failures are never anything; mandate notices come before the "upcoming payment" drop rule,
        // because a pre-debit notice is exactly that (AOS-502).
        neverFinancial(text)?.let { return SmsParseResult.Ignored(it) }
        MandateSmsParser.parse(text, parser?.bankCode)?.let { return SmsParseResult.Mandate(it) }
        DROP_RULES.firstOrNull { (applies, _) -> applies(text) }?.let { return SmsParseResult.Ignored(it.second) }

        // A promised credit is no transaction; only its clause is dropped, so a real debit beside it is still read (AOS-213).
        val promiseless = PromisedCredits.strip(text)
        val body = promiseless ?: text
        val reading = parser?.read(body)?.takeUnless { it == SmsReading.NotATransaction } ?: generic.read(body)
        return when (reading) {
            is SmsReading.Parsed -> SmsParseResult.Transaction(reading.transaction.copy(markers = MandateDebitMarker.metadataFor(text)))
            SmsReading.DirectionUnclear -> SmsParseResult.Ignored(SmsParseResult.DIRECTION_UNCLEAR)
            SmsReading.NotATransaction ->
                if (promiseless != null) SmsParseResult.Ignored(SmsParseResult.PROMISED_CREDIT) else SmsParseResult.Unrecognised
        }
    }

    companion object {
        private val I = RegexOption.IGNORE_CASE
        /** Provider messages are Indian: a due date without a year is read in India's calendar. */
        private val INDIA: java.time.ZoneId = java.time.ZoneId.of("Asia/Kolkata")

        /** Never anything, not even a mandate notice. */
        private val BEFORE_MANDATES: List<Pair<Regex, String>> = listOf(
            Regex("\\b(OTP|one[ -]time password|verification code)\\b", I) to "one-time password",
            Regex("\\b(declined|failed|unsuccessful|could not be processed)\\b", I) to "failed or declined transaction"
        )

        private val BALANCE = Regex("\\b(available balance|avl\\.? bal|avail(able)? bal)", I)

        /** Checked before parsing transactions. Order matters only for the reason reported. */
        private val DROP_RULES: List<Pair<(String) -> Boolean, String>> = listOf(
            Regex("\\b(collect request|has requested (money|Rs|INR)|requested money)\\b", I)::containsMatchIn to "payment request, not a payment",
            Regex("\\b(will be debited|is due|due on|auto-?debit (is )?scheduled|reminder)\\b", I)::containsMatchIn to "upcoming payment notice",
            // Only a balance: a transaction that also shows "Avl Bal" ("Dr. Rs 500 … Avl Bal Rs 4,500") is kept (AOS-214).
            { text: String -> BALANCE.containsMatchIn(text) && !SmsSignals.mentionsTransaction(text) } to SmsParseResult.BALANCE_ALERT
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
