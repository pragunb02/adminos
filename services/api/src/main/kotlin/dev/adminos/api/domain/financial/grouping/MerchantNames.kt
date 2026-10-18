package dev.adminos.api.domain.financial.grouping

/**
 * A merchant name reduced to the words that tell one service from another (AOS-709): "NETFLIX.COM",
 * "Netflix Ent" and "NETFLIX ENTERTAINMENT SERVICES INDIA PVT LTD" are all `[netflix]`, while
 * "Amazon Prime" and "Amazon Pay" stay `[amazon, prime]` and `[amazon, pay]`.
 */
data class CleanName(
    val tokens: List<String>,
    /**
     * The raw name had invisible characters or letters outside the Latin alphabet ("Netflіx" with a
     * Cyrillic і, or a zero-width space): it could be made to look like another merchant, so it is never
     * proposed for a merge (AOS-709 security review).
     */
    val confusable: Boolean = false
) {
    init {
        require(tokens.isNotEmpty() && tokens.all { it.isNotBlank() }) { "A clean name has at least one word" }
    }

    /** Words joined without spaces: "Big Basket" and "BigBasket" are the same `bigbasket`. */
    val compact: String get() = tokens.joinToString("")
    val text: String get() = tokens.joinToString(" ")
}

/** Cleans merchant names for comparison only; what the user sees is never changed by it. */
object MerchantNameCleaner {
    /**
     * Words that never tell two services apart: web and legal suffixes, "india", business filler, and
     * payment rails. Dropped only while other words remain, so "Online Services" isn't emptied.
     */
    private val GENERIC = setOf(
        "com", "co", "in", "net", "org", "io", "app", "www", "http", "https",
        "india", "ind", "bharat", "pvt", "private", "ltd", "limited", "llp", "inc", "llc", "corp", "corporation", "company",
        "ab", "ag", "gmbh", "plc", "bv", "sa", "sas", "oy", "kk", "pty", "pte", "the", "and",
        "services", "service", "technologies", "technology", "tech", "solutions", "enterprises", "labs", "innovations", "digital", "online", "intl", "international",
        "ent", "entertainment", "media", "subscription", "subscriptions", "payments", "payment",
        "upi", "pos", "ecom", "autopay", "mandate", "si", "recurring", "bill", "ref",
        // honorifics: "Shree Krishna Dairy" is "Shri Krishna Dairy"
        "shri", "shree", "sri", "sree", "smt", "mr", "mrs", "ms", "dr"
    )

    /** Payment gateways and wallets printed in front of the real merchant ("PAYPAL *SPOTIFY"). */
    val GATEWAYS = setOf(
        "razorpay", "raz", "rzp", "payu", "paytm", "cashfree", "billdesk", "ccavenue", "phonepe", "juspay", "easebuzz",
        "paypal", "stripe", "gpay", "googlepay", "paypl"
    )

    /** Suffixes glued to a name by banks ("SPOTIFYINDIA", "NETFLIXCOM"), cut when 4+ letters remain. */
    private val GLUED = listOf("india", "online", "com", "pvt", "ltd")

    /** Longest name compared; longer ones (no real merchant name is) are cut, so the work stays bounded. */
    const val MAX_LENGTH = 120

    fun clean(name: String): CleanName? {
        val folded = java.text.Normalizer.normalize(name.take(MAX_LENGTH * 2), java.text.Normalizer.Form.NFKC).take(MAX_LENGTH)
        val confusable = folded.any { Character.getType(it) == Character.FORMAT.toInt() } || folded.any { it.isLetter() && it.code > 0x7F }
        val words = folded.lowercase().replace('&', ' ').split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val unglued = words.map(::unglue)
        // A lone initial ("RAMESH KUMAR S") doesn't tell names apart; a lone digit ("ZEE 5") does.
        val distinctive = unglued.filter { it !in GENERIC && it !in GATEWAYS && !(it.length == 1 && it[0].isLetter()) }
        return CleanName(distinctive.ifEmpty { unglued.filter { it !in GATEWAYS }.ifEmpty { unglued } }, confusable)
    }

    private fun unglue(word: String): String {
        var w = word
        var changed = true
        while (changed) {
            changed = false
            for (suffix in GLUED) {
                if (w.length >= suffix.length + 4 && w.endsWith(suffix)) {
                    w = w.dropLast(suffix.length)
                    changed = true
                }
            }
        }
        return w
    }
}
