package dev.adminos.api.domain.ingestion.cash

import java.math.BigDecimal
import java.text.Normalizer

/**
 * Normalizes cash text before it's read (AOS-718): lower case, Unicode-normalized (so dictation's
 * full-width and Devanagari digits read as digits), number words as digits, digit grouping removed,
 * and a word glued to a number ("chai40", "40rs") split apart. Pure.
 */
object PhraseText {
    private val DEVANAGARI_DIGITS = "०१२३४५६७८९"
    /** 1,200 · 1,20,000 · 12,000: grouping, not a list. */
    private val GROUPED = Regex("(?<![\\d,])\\d{1,3}(?:,\\d{2})*,\\d{3}(?![\\d,])|(?<![\\d,])\\d{1,3}(?:,\\d{3})+(?![\\d,])")
    private val LETTER_DIGIT = Regex("(?<=[a-z₹])(?=\\d)|(?<=\\d)(?=[a-z₹])")
    private val ORDINAL_GLUE = Regex("\\b(\\d{1,2}) (st|nd|rd|th)\\b")
    private val CURRENCY_DOT = Regex("\\brs\\.")
    private val FORMAT_CHARS = Regex("\\p{Cf}")

    fun normalize(text: String): String {
        // Invisible format characters (zero-width spaces, right-to-left overrides) can hide or reorder text: dropped.
        var s = Normalizer.normalize(text, Normalizer.Form.NFKC).replace(FORMAT_CHARS, "").lowercase()
        s = s.map { c -> DEVANAGARI_DIGITS.indexOf(c).takeIf { it >= 0 }?.let { '0' + it } ?: c }.joinToString("")
        s = s.replace('’', '\'').replace(CURRENCY_DOT, "rs ")
        s = GROUPED.replace(s) { it.value.replace(",", "") }
        s = LETTER_DIGIT.replace(s, " ")
        s = ORDINAL_GLUE.replace(s) { "${it.groupValues[1]}${it.groupValues[2]}" }
        s = NumberWords.toDigits(s)
        return s.replace(Regex("\\s+"), " ").trim()
    }
}

/**
 * Spoken or typed number words as digits: English ("two hundred", "fifty", "one thousand five hundred") and
 * Hinglish multiples ("do sau", "dedh sau" = 150, "dhai hazaar" = 2,500, "paanch sau"). Hindi small numbers
 * are read only before "sau" or "hazaar", because alone they're ordinary words ("do", "char").
 */
object NumberWords {
    private val UNITS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19
    )
    private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private val HINDI = mapOf(
        "ek" to "1", "do" to "2", "teen" to "3", "char" to "4", "chaar" to "4", "paanch" to "5", "panch" to "5", "chhe" to "6", "che" to "6",
        "saat" to "7", "aath" to "8", "nau" to "9", "das" to "10", "dedh" to "1.5", "dhai" to "2.5", "dhaai" to "2.5", "sava" to "1.25"
    )
    private val HINDI_MULTIPLE = Regex("\\b(${HINDI.keys.joinToString("|")}|\\d+(?:\\.\\d+)?)\\s+(sau|hazaar|hazar|hajar)\\b")
    private val ENGLISH = Regex("\\b(?:a\\s+)?(?:(?:${(UNITS.keys + TENS.keys + listOf("hundred", "thousand", "lakh")).joinToString("|")})(?:[\\s-]+)?)+\\b")

    fun toDigits(s: String): String {
        val hindi = HINDI_MULTIPLE.replace(s) { m ->
            val n = BigDecimal(HINDI[m.groupValues[1]] ?: m.groupValues[1])
            val multiple = if (m.groupValues[2] == "sau") BigDecimal(100) else BigDecimal(1000)
            n.multiply(multiple).stripTrailingZeros().toPlainString()
        }
        return ENGLISH.replace(hindi) { m -> english(m.value)?.let { "$it " } ?: m.value }
    }

    /** "two hundred fifty" → 250; null when the words don't make a number. ("and" isn't read: it separates entries.) */
    private fun english(phrase: String): Long? {
        val words = phrase.split(Regex("[\\s-]+")).filter { it.isNotEmpty() }
        // "1.5 lakh" and "2 thousand" are digits with a multiplier, read with the amount: a multiplier alone isn't a number.
        if (words.none { it in UNITS || it in TENS || it == "a" }) return null
        var total = 0L
        var current = 0L
        var any = false
        for (w in words) {
            when (w) {
                "a" -> current = maxOf(current, 1)
                "hundred" -> { current = maxOf(current, 1) * 100; any = true }
                "thousand" -> { total += maxOf(current, 1) * 1000; current = 0; any = true }
                "lakh" -> { total += maxOf(current, 1) * 100_000; current = 0; any = true }
                else -> {
                    current += UNITS[w]?.toLong() ?: TENS[w]?.toLong() ?: return null
                    any = true
                }
            }
        }
        return if (any) total + current else null
    }
}
