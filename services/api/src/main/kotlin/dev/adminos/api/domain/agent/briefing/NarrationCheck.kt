package dev.adminos.api.domain.agent.briefing

import dev.adminos.api.domain.ai.AiReasonCode
import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

/**
 * The number and name check for a briefing narration (AOS-716, D-67: "every number in generated prose must be in the
 * facts given"; D-140).
 *
 * **Numbers.** A narration is grounded only if every number in it is one of the numbers it was given: an amount, a count,
 * a day of the month, a rank or a digit in a name it was shown. Matching is on the value, so `₹1,23,456.50`, `123456.5`
 * and `Rs 123456.50` are the same number while a rounded `₹1,23,000` is not. A number written as money (after `₹`, `Rs`,
 * `INR`, or before `rupees`, `/-`) must be one of the **amounts**, so a count or a date can't pass as money. It fails
 * closed on what it can't check: a number with a scale word (`4.1k`, `2 lakh`), a spelled-out large number (`twelve
 * hundred`) and `two`…`ten` written as words unless that number is a fact. Digits of any script are read as digits.
 *
 * **Names.** Every capitalised word that doesn't start a sentence must be a word of a name or label it was given, or one
 * of [FIXED_WORDS] (months, weekdays, UPI…). This catches "most went to Zomato" when Zomato isn't a fact; a lowercase or
 * sentence-initial invented name isn't caught (the eval measures it, and the plain numbers are one click away).
 *
 * The approach follows AOS-710's Ask answer check (`AnswerGrounding`, PR #124); once both are on main they can share one
 * checker.
 */
object NarrationCheck {
    val UNGROUNDED_NUMBER = AiReasonCode("ungrounded_number")
    val SCALED_NUMBER = AiReasonCode("scaled_number")
    val SPELLED_NUMBER = AiReasonCode("spelled_number")
    val MISSING_TOTAL = AiReasonCode("missing_total")
    val UNKNOWN_NAME = AiReasonCode("unknown_name")

    /** The numbers a narration may contain: [amounts] (money) and [any] (amounts plus counts, days, ranks, digits in names). */
    data class Allowed(val amounts: Set<BigDecimal>, val any: Set<BigDecimal>) {
        init {
            require(any.containsAll(amounts)) { "Every amount is also allowed as a plain number" }
        }
    }

    private val NUMBER = Regex("(?<![\\d.,])(\\d{1,3}(?:,\\d{2,3})+|\\d+)(?:\\.(\\d+))?")
    private val SCALE = Regex("^\\s*(?:k|l|lakh|lakhs|lac|lacs|cr|crore|crores|thousand|million|mn|bn|billion|hazaar|hazar|hajar|sau|hundred|%|percent|per cent)(?![\\p{L}])", RegexOption.IGNORE_CASE)
    private val WORD = Regex("\\p{L}[\\p{L}\\p{M}]*")
    /** Money marks are looked for this many characters either side of a number. */
    private const val MARK_WINDOW = 8
    private val MONEY_BEFORE = Regex("(?:₹|\\brs\\.?|\\binr)\\s?[-−]?\\s?$", RegexOption.IGNORE_CASE)
    private val MONEY_AFTER = Regex("^\\s?(?:rupees?\\b|rupaye\\b|rs\\b|/-)", RegexOption.IGNORE_CASE)

    private val SMALL_WORDS = mapOf(
        "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10
    )
    private val LARGE_WORDS = setOf(
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
        "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
        "hundred", "thousand", "lakh", "lakhs", "lac", "lacs", "crore", "crores", "million", "billion",
        "hazaar", "hazar", "hajar", "sau", "dozen", "half", "double", "twice", "triple", "percent"
    )

    /** Capitalised words any narration may use mid-sentence: dates, and a few fixed terms of Indian payments. */
    val FIXED_WORDS: Set<String> = (
        listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
            "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "mon", "tue", "tues", "wed", "thu", "thur", "thurs", "fri", "sat", "sun",
            "upi", "autopay", "emi", "inr", "rs", "i")
        ).toSet()

    /** The numbers written in [text] with digits, as values (`1,234.50` → 1234.5). */
    fun numbersIn(text: String): List<BigDecimal> = NUMBER.findAll(asciiDigits(Normalizer.normalize(text, Normalizer.Form.NFKC))).map(::value).toList()

    /** Null when every number in [text] is allowed; otherwise why not. */
    fun numbers(text: String, allowed: Allowed): AiReasonCode? {
        val normal = asciiDigits(Normalizer.normalize(text, Normalizer.Form.NFKC))
        for (m in NUMBER.findAll(normal)) {
            val after = normal.substring(m.range.last + 1)
            if (SCALE.containsMatchIn(after)) return SCALED_NUMBER
            val before = normal.substring(0, m.range.first).takeLast(MARK_WINDOW)
            val isMoney = MONEY_BEFORE.containsMatchIn(before) || MONEY_AFTER.containsMatchIn(after.take(MARK_WINDOW))
            if (value(m) !in (if (isMoney) allowed.amounts else allowed.any)) return UNGROUNDED_NUMBER
        }
        for (w in WORD.findAll(normal)) {
            val word = w.value.lowercase(Locale.ROOT)
            if (word in LARGE_WORDS) return SPELLED_NUMBER
            val small = SMALL_WORDS[word] ?: continue
            if (BigDecimal(small) !in allowed.any) return UNGROUNDED_NUMBER
        }
        return null
    }

    /** Null when [text] states [amount] (when there is one); otherwise [MISSING_TOTAL]. */
    fun mentions(text: String, amount: BigDecimal?): AiReasonCode? =
        if (amount == null || numbersIn(text).any { it.compareTo(norm(amount)) == 0 }) null else MISSING_TOTAL

    /**
     * Null when every capitalised word that doesn't start a sentence is in [vocabulary] (lowercase words of the names and
     * labels sent) or [FIXED_WORDS]; otherwise [UNKNOWN_NAME].
     */
    fun names(text: String, vocabulary: Set<String>): AiReasonCode? {
        val normal = Normalizer.normalize(text, Normalizer.Form.NFKC)
        for (w in WORD.findAll(normal)) {
            if (!Character.isUpperCase(w.value.codePointAt(0)) || startsSentence(normal, w.range.first)) continue
            val word = w.value.lowercase(Locale.ROOT)
            if (word !in vocabulary && word !in FIXED_WORDS) return UNKNOWN_NAME
        }
        return null
    }

    /** The lowercase words of [text], for a name check vocabulary. */
    fun wordsOf(text: String): Set<String> =
        WORD.findAll(Normalizer.normalize(text, Normalizer.Form.NFKC)).map { it.value.lowercase(Locale.ROOT) }.toSet()

    /** The value a number is compared by: no sign, no trailing zeros. */
    fun norm(n: BigDecimal): BigDecimal = n.abs().stripTrailingZeros()

    private fun startsSentence(text: String, at: Int): Boolean {
        var i = at - 1
        while (i >= 0 && (text[i].isWhitespace() && text[i] != '\n' || text[i] in OPENERS)) i--
        return i < 0 || text[i] in SENTENCE_ENDS
    }

    private const val OPENERS = "\"'‘“("
    private const val SENTENCE_ENDS = ".!?\n"

    private fun value(m: MatchResult): BigDecimal {
        val whole = m.groupValues[1].replace(",", "")
        val fraction = m.groupValues[2]
        return BigDecimal(if (fraction.isEmpty()) whole else "$whole.$fraction").stripTrailingZeros()
    }

    /** Digits of any script become ASCII digits, so `५००` is read as 500 (NFKC leaves them as they are). */
    private fun asciiDigits(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isDigit(cp)) sb.append(('0'.code + Character.digit(cp, 10)).toChar()) else sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}
