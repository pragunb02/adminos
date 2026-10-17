package dev.adminos.api.domain.financial.learning

import java.math.BigDecimal

/**
 * One sparse feature of a [CategoryInput], and the word it came from ([cue]), so a guess can say *why*:
 * "Health, because of 'medicos'". Method, amount and direction features have no cue.
 */
data class Feature(val name: String, val cue: String? = null) {
    init {
        require(name.isNotBlank()) { "A feature has a name" }
    }
}

/**
 * Turns a payment into the features the learned categoriser uses (AOS-707). Deterministic and pure.
 * - `w:` whole words of the cleaned payee name, and `c:` their character 3–5-grams, which carry misspellings,
 *   truncation and related words ("medicos" shares "medic" with "medical");
 * - `h:` the words of the UPI handle and `hc:` their character n-grams, which find brands glued into a handle
 *   ("netflixupi"); `h:#phone` marks a phone-number handle (usually a person), `psp:` the app or bank after `@`;
 * - `n:` other words of the bank narration ("LUNCH", "ORDER"), with channel words, bank codes and references removed;
 * - `pm:` the payment method, `nat:` spend or refund, and `amt:` an amount bucket.
 */
object CategoryFeatures {
    private const val MIN_GRAM = 3
    private const val MAX_GRAM = 5
    private const val MIN_WORD = 2
    private const val MIN_NARRATION_WORD = 3
    /** Longer "words" are references or junk, and would make feature names unbounded. */
    const val MAX_WORD = 40

    /** Upper bounds of the amount buckets in rupees; above the last is the top bucket. */
    private val AMOUNT_BUCKETS = listOf(100, 500, 2_000, 10_000, 50_000).map { BigDecimal(it) }

    private val WORD_SPLIT = Regex("[^a-z0-9]+")
    private val HANDLE_SPLIT = Regex("[^a-z]+")
    private val VPA_IN_TEXT = Regex("[A-Za-z0-9._-]{2,}@[A-Za-z]{2,}")
    private val PHONE_HANDLE = Regex("^\\+?\\d{10,12}$")
    private val DIGITS = Regex("^\\d+$")

    /** Words that are bank structure, not the payee (the same idea as `NarrationCleaner`). */
    private val NARRATION_NOISE = setOf(
        "upi", "dr", "cr", "pos", "vps", "imps", "neft", "rtgs", "ach", "nach", "ecs", "ecom", "pur", "purchase", "billpay",
        "onl", "inb", "tpt", "trf", "to", "from", "by", "ref", "txn", "payment", "pay", "paid", "sent", "collect", "request",
        "null", "inr", "rs", "the", "and", "for", "netbank", "debit", "credit", "card",
        "hdfc", "icic", "icici", "sbin", "sbi", "utib", "axis", "kkbk", "kotak", "yesb", "punb", "barb", "cnrb", "ubin",
        "idfb", "indb", "fdrl", "pytm", "airp", "idib", "ioba", "mahb", "bkid", "cbin", "ratn", "aubl", "esfb", "jiop", "fino"
    )

    /**
     * Words that say nothing about what was bought, but are shared by unrelated payees: "India" is in
     * "Air India" and "Nippon India MF", "general" in "general store" and "General Insurance" (AOS-707 found these
     * causing confident mistakes). Legal and corporate suffixes are here for the same reason.
     */
    private val GENERIC_WORDS = setOf(
        "india", "indian", "ind", "the", "and", "of", "pvt", "ltd", "limited", "private", "company", "co", "llp", "inc", "corp",
        "general", "personal", "services", "service", "solutions", "enterprises", "enterprise", "international", "group", "new", "shree", "sri"
    )

    fun of(input: CategoryInput): List<Feature> {
        val nameWords = words(input.merchantName).filter { it !in GENERIC_WORDS }
        val handle = input.upiVpa?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val features = buildList {
            nameWords.forEach { w ->
                add(Feature("w:$w", w))
                grams(w).forEach { add(Feature("c:$it", w)) }
            }
            handle?.let { addAll(handleFeatures(it)) }
            narrationWords(input.merchantRaw, nameWords.toSet()).forEach { add(Feature("n:$it", it)) }
            input.paymentMethod?.let { add(Feature("pm:${it.name.lowercase()}")) }
            add(Feature("nat:${input.nature.name.lowercase()}"))
            input.amount?.let { add(Feature("amt:${bucketOf(it)}")) }
        }
        return features.distinctBy { it.name }
    }

    /** The bucket index of [amount]: 0 for under ₹100 … 5 for ₹50,000 and above. */
    fun bucketOf(amount: BigDecimal): Int = AMOUNT_BUCKETS.indexOfFirst { amount < it }.let { if (it < 0) AMOUNT_BUCKETS.size else it }

    private fun words(text: String?): List<String> =
        text?.lowercase()?.split(WORD_SPLIT)?.filter { it.length in MIN_WORD..MAX_WORD && !DIGITS.matches(it) }.orEmpty()

    /** Character n-grams of one word, with `^` and `$` marking its start and end. */
    private fun grams(word: String): List<String> {
        val padded = "^$word$"
        return (MIN_GRAM..MAX_GRAM).flatMap { n -> if (padded.length < n) emptyList() else (0..padded.length - n).map { padded.substring(it, it + n) } }
    }

    private fun handleFeatures(vpa: String): List<Feature> = buildList {
        val local = vpa.substringBefore('@')
        val provider = vpa.substringAfter('@', "").filter { it.isLetter() }
        if (provider.length in MIN_WORD..MAX_WORD) add(Feature("psp:$provider"))
        if (PHONE_HANDLE.matches(local)) {
            add(Feature("h:#phone"))
            return@buildList
        }
        local.split(HANDLE_SPLIT).filter { it.length in MIN_WORD..MAX_WORD && it !in GENERIC_WORDS }.forEach { w ->
            add(Feature("h:$w", w))
            grams(w).forEach { add(Feature("hc:$it", w)) }
        }
    }

    /** Narration words that add something: not the payee name again, not bank structure, not a reference. */
    private fun narrationWords(raw: String?, nameWords: Set<String>): List<String> =
        words(raw?.replace(VPA_IN_TEXT, " "))
            .filter { it.length >= MIN_NARRATION_WORD && it.any(Char::isLetter) && it.count(Char::isDigit) == 0 }
            .filter { it !in nameWords && it !in NARRATION_NOISE && it !in GENERIC_WORDS }
            .distinct()
}
