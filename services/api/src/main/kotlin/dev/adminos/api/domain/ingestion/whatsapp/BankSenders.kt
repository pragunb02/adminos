package dev.adminos.api.domain.ingestion.whatsapp

/**
 * Decides whether a WhatsApp sender is a bank, from its display name (AOS-403). WhatsApp has no TRAI header,
 * so only the name tells. Anyone else (friends, groups, shops) is never parsed, so "I paid 500" from a friend
 * can't become a transaction. A new bank is one entry in [KNOWN].
 */
object BankSenders {
    sealed interface Match
    /** A known bank; its own parser is used. */
    data class Bank(val code: String) : Match
    /** Looks like a bank or card issuer but isn't known; the generic parser is used. */
    data object UnknownBank : Match

    private val KNOWN: List<Pair<Regex, String>> = listOf(
        "hdfc" to "hdfc",
        "sbi|state bank" to "sbi",
        "icici" to "icici",
        "axis" to "axis",
        "kotak" to "kotak"
    ).map { (pattern, code) -> Regex("\\b($pattern)", RegexOption.IGNORE_CASE) to code }

    private val BANK_LIKE = Regex("\\b(bank|card|credit|finance)\\b", RegexOption.IGNORE_CASE)

    fun match(sender: String): Match? {
        KNOWN.firstOrNull { (re, _) -> re.containsMatchIn(sender) }?.let { return Bank(it.second) }
        return if (BANK_LIKE.containsMatchIn(sender)) UnknownBank else null
    }
}
