package dev.adminos.api.domain.ask

import dev.adminos.api.domain.ai.AiReasonCode
import dev.adminos.api.domain.ai.SafeName
import java.math.BigDecimal
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

/**
 * The number check (D-67: "every number in an Ask answer must come from a tool result"; AOS-712/710).
 *
 * An answer is **grounded** only if every number in it is a number the tools returned: an amount, a count, a
 * percent the server computed, a date part of a period shown, a rank within a list shown, or a digit in a
 * merchant name the model was shown. Matching is on the value, so `₹1,23,456.50`, `123456.5` and `Rs 123456.50`
 * are the same number, while a rounded `₹1,23,000` is not.
 *
 * A number written as money (after `₹`, `Rs`, `INR`, or before `rupees`, `/-`) must be one of the **amounts**,
 * so a count, a date or a rank can't pass as money (`₹1` is not grounded by "1st").
 *
 * It fails closed on what it can't check:
 * - a number with a scale word (`1.2L`, `5k`, `2 crore`, `3 hazaar`) or a spelled-out large number
 *   (`twelve hundred`, `fifty`) is ungrounded, because the model was told to copy figures as given;
 * - `two` … `ten` count as numbers and must be grounded like digits;
 * - digits in any script are read as digits (Devanagari `५००` is 500).
 * A grounded answer can still be worded wrongly; the eval set measures that (AOS-712). This check guarantees
 * only that no figure was invented.
 */
object AnswerGrounding {
    val UNGROUNDED_NUMBER = AiReasonCode("ungrounded_number")
    val SCALED_NUMBER = AiReasonCode("scaled_number")
    val SPELLED_NUMBER = AiReasonCode("spelled_number")

    sealed interface Verdict {
        data object Grounded : Verdict

        data class Ungrounded(val code: AiReasonCode) : Verdict
    }

    private val NUMBER = Regex("(?<![\\d.,])(\\d{1,3}(?:,\\d{2,3})+|\\d+)(?:\\.(\\d+))?")
    private val SCALE = Regex("^\\s*(?:k|l|lakh|lakhs|lac|lacs|cr|crore|crores|thousand|million|mn|bn|billion|hazaar|hazar|hajar|sau|hundred)(?![\\p{L}])", RegexOption.IGNORE_CASE)
    private val WORD = Regex("\\p{L}+")
    /** Money marks are looked for this many characters either side of a number. */
    private const val MARK_WINDOW = 8
    /** Money marks before a number: `₹`, `Rs`, `Rs.`, `INR` (NFKC turns `₨` into `Rs`), with an optional minus or space. */
    private val MONEY_BEFORE = Regex("(?:₹|\\brs\\.?|\\binr)\\s?[-−]?\\s?$", RegexOption.IGNORE_CASE)
    /** Money marks after a number: `rupees`, `rupaye`, `rs`, `/-`. */
    private val MONEY_AFTER = Regex("^\\s?(?:rupees?\\b|rupaye\\b|rs\\b|/-)", RegexOption.IGNORE_CASE)

    private val SMALL_WORDS = mapOf(
        "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10
    )
    private val LARGE_WORDS = setOf(
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
        "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
        "hundred", "thousand", "lakh", "lakhs", "lac", "lacs", "crore", "crores", "million", "billion",
        "hazaar", "hazar", "hajar", "sau", "dozen"
    )

    /** The numbers an answer may contain: [amounts] (money) and [any] (amounts plus counts, dates, ranks, percents). */
    data class Allowed(val amounts: Set<BigDecimal>, val any: Set<BigDecimal>)

    /** Every number an answer may contain, given what the tools returned and the server compared. */
    fun allowed(results: List<AskToolResult>, comparisons: List<AskComparison> = AskComparison.of(results)): Allowed {
        val amounts = mutableSetOf<BigDecimal>()
        val other = mutableSetOf<BigDecimal>()
        fun money(n: BigDecimal) { amounts += norm(n) }
        fun add(n: BigDecimal) { other += norm(n) }
        fun add(n: Int) = add(BigDecimal(n))
        fun add(day: LocalDate) { add(day.dayOfMonth); add(day.monthValue); add(day.year) }
        fun names(name: String) { if (SafeName.isSafe(name)) numbersIn(name).forEach(::add) }
        fun ranks(n: Int) { for (i in 1..n) add(i) }
        for (r in results) {
            add(r.range.from); add(r.range.to); add(r.range.days.toInt())
            r.total?.let(::money)
            when (r) {
                is AskToolResult.Summary -> { money(r.spent); money(r.received); add(r.payments) }
                is AskToolResult.Categories -> { ranks(r.rows.size); r.rows.forEach { money(it.spent); add(it.payments) } }
                is AskToolResult.Merchant -> { ranks(r.rows.size); r.rows.forEach { money(it.spent); add(it.payments); names(it.name) } }
                is AskToolResult.TopMerchants -> { ranks(r.rows.size); add(r.call.limit); r.rows.forEach { money(it.spent); add(it.payments); names(it.name) } }
                is AskToolResult.Trend -> { ranks(r.points.size); r.points.forEach { add(it.start); money(it.spent) } }
            }
            (r.call as? AskToolCall.MerchantSpend)?.let { names(it.merchant.name) }
        }
        for (c in comparisons) { money(c.difference); c.changePct?.let(::add) }
        return Allowed(amounts.toSet(), (amounts + other).toSet())
    }

    fun check(answer: String, allowed: Allowed): Verdict {
        val text = asciiDigits(Normalizer.normalize(answer, Normalizer.Form.NFKC))
        for (m in NUMBER.findAll(text)) {
            val after = text.substring(m.range.last + 1)
            if (SCALE.containsMatchIn(after)) return Verdict.Ungrounded(SCALED_NUMBER)
            val isMoney = MONEY_BEFORE.containsMatchIn(text.substring(0, m.range.first).takeLast(MARK_WINDOW)) || MONEY_AFTER.containsMatchIn(after.take(MARK_WINDOW))
            if (value(m) !in (if (isMoney) allowed.amounts else allowed.any)) return Verdict.Ungrounded(UNGROUNDED_NUMBER)
        }
        for (w in WORD.findAll(text)) {
            val word = w.value.lowercase(Locale.ROOT)
            if (word in LARGE_WORDS) return Verdict.Ungrounded(SPELLED_NUMBER)
            val small = SMALL_WORDS[word] ?: continue
            if (BigDecimal(small) !in allowed.any) return Verdict.Ungrounded(UNGROUNDED_NUMBER)
        }
        return Verdict.Grounded
    }

    private fun norm(n: BigDecimal) = n.abs().stripTrailingZeros()

    /** The numbers written in [text] with digits, as values (`1,234.50` → 1234.5). */
    fun numbersIn(text: String): List<BigDecimal> =
        NUMBER.findAll(asciiDigits(Normalizer.normalize(text, Normalizer.Form.NFKC))).map(::value).toList()

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
