package dev.adminos.api.domain.ingestion.pipeline

/**
 * Pulls the payee name out of a bank narration (AOS-206). Banks wrap it in structure:
 * - HDFC UPI: `UPI-RAMU CHAIWALA-ramu@okicici-ICIC0001234-123456789012-PAYMENT`
 * - SBI UPI:  `UPI/DR/123456789012/RAMU CHAI/YESB/ramu@ybl/Payment`
 * - cards:    `POS 512345XXXXXX1234 STARBUCKS COFFEE BANGALORE`
 * - gateways: `RAZ*SWIGGY`, `PAYU*NYKAA`
 * Splits on `-` and `/`, drops UPI IDs, then trims IFSC codes, references, masked cards, bank codes and
 * channel words from the ends of each part (inner words are kept: "BACK TO BASICS" stays whole),
 * then strips legal suffixes and a trailing city. Returns null if nothing is left.
 */
object NarrationCleaner {

    private val CHANNEL_WORDS = setOf(
        "UPI", "DR", "CR", "POS", "VPS", "IMPS", "NEFT", "RTGS", "ACH", "NACH", "ECS", "ECOM", "PUR", "PURCHASE", "IB", "MB",
        "BIL", "BILLPAY", "ONL", "INB", "TPT", "TRF", "TO", "FROM", "BY", "REF", "TXN", "PAYMENT", "PAY", "PAID",
        "SENT", "COLLECT", "REQUEST", "NA", "NULL", "P2A", "P2M", "P2P", "INR", "RS"
    )
    private val BANK_CODES = setOf(
        "HDFC", "ICIC", "ICICI", "SBIN", "SBI", "UTIB", "AXIS", "KKBK", "KOTAK", "YESB", "PUNB", "BARB", "CNRB", "UBIN",
        "IDFB", "INDB", "FDRL", "PYTM", "AIRP", "IDIB", "IOBA", "MAHB", "BKID", "CBIN", "RATN", "AUBL", "ESFB", "JIOP", "FINO"
    )
    private val GATEWAYS = Regex("^(RAZ|RAZORPAY|RSP|PAYU|CASHFREE|CF|BILLDESK|BD|CCAVENUE|CCA|PAYTM|PTM|PHONEPE|JUSPAY|EASEBUZZ|PAYGLOCAL|SQ|SP|PP|GOOGLE)\\s*\\*\\s*", RegexOption.IGNORE_CASE)
    private val LEGAL_SUFFIX = Regex("\\s+(PVT\\.?\\s*LTD\\.?|PRIVATE\\s+LIMITED|PVT|LTD\\.?|LIMITED|LLP|INC\\.?|CO\\.?|CORP\\.?|INDIA|IND)$", RegexOption.IGNORE_CASE)
    private val CITY_SUFFIX = Regex(
        "\\s+(BANGALORE|BENGALURU|BLR|MUMBAI|BOMBAY|DELHI|NEW DELHI|GURGAON|GURUGRAM|NOIDA|PUNE|HYDERABAD|CHENNAI|KOLKATA|AHMEDABAD|JAIPUR|IN)$",
        RegexOption.IGNORE_CASE
    )
    private val VPA = Regex("(?<![A-Za-z0-9._])[A-Za-z0-9._]{2,}@[A-Za-z]{2,}") // same rule as the SMS/statement parsers
    private val IFSC = Regex("^[A-Z]{4}0[A-Z0-9]{6}$")
    private val MASKED_OR_REF = Regex("^[0-9Xx*]{6,}$")
    private val HAS_LETTERS = Regex("[A-Za-z].*[A-Za-z]")

    fun clean(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val text = GATEWAYS.replace(VPA.replace(raw, " ").trim(), "")
        val parts = text.split('-', '/', '|', ':')
            .map { part -> part.trim().split(Regex("\\s+")).dropWhile(::isNoise).dropLastWhile(::isNoise).joinToString(" ") }
            .filter { HAS_LETTERS.containsMatchIn(it) }
        var name = parts.firstOrNull() ?: return null
        repeat(3) { name = LEGAL_SUFFIX.replace(CITY_SUFFIX.replace(name, ""), "").trim() }
        return name.trim(' ', '*', '.', ',').takeIf { HAS_LETTERS.containsMatchIn(it) }
    }

    private fun isNoise(word: String): Boolean {
        val w = word.trim(' ', '*', '.', ',', '#')
        if (w.isEmpty()) return true
        val upper = w.uppercase()
        return upper in CHANNEL_WORDS || upper in BANK_CODES || IFSC.matches(upper) || MASKED_OR_REF.matches(w) ||
            (w.any(Char::isDigit) && w.count(Char::isLetter) <= 1) // "S1ANBG01"-style terminal IDs kept; "1234" or "X1234" dropped
    }
}
