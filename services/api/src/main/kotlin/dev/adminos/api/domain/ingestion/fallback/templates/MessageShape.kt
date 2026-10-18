package dev.adminos.api.domain.ingestion.fallback.templates

import dev.adminos.api.domain.ingestion.fallback.MessageAnswer
import dev.adminos.api.domain.ingestion.fallback.MessageGrounding

/**
 * The masked **shape** of a bank message (AOS-706, D-107): the redacted text with every value replaced by a slot,
 * so only the bank's own wording is left. `Card XX4321 charged Rs.249.00 at ZEPTO on 16-10-26. Avl Lmt Rs 45,000.00`
 * becomes `Card XX<ACCT> charged Rs.<AMT> at <M> on <DATE>. Avl Lmt Rs <BAL>`.
 *
 * Slots: [AMT] the amount moved, [BAL] any other money figure, [ACCT] the account's last 4, [DATE] a date, [M] the payee
 * or payer, [N] any other number, [NAME] the word after a greeting ("Dear X"), [ANY] a word that varies between
 * messages of one format (from merging, [MessageShapes.merge]). A shape holds **no digits, no amounts, no merchant and
 * no message text** (D-33): it is built only from a redacted message and checked to have no digit left.
 */
@JvmInline
value class MessageShape(val text: String) {
    init {
        require(text.length in 1..MAX_LENGTH) { "A shape is 1..$MAX_LENGTH characters" }
        require(text.none { it.isDigit() }) { "A shape holds no digits" }
        require(AMT in text) { "A shape has the amount slot" }
    }

    val tokens: List<String> get() = text.split(' ')

    override fun toString() = text // a shape is safe to show: no values, only the bank's wording

    companion object {
        const val MAX_LENGTH = 1_000
        const val AMT = "<AMT>"
        const val BAL = "<BAL>"
        const val ACCT = "<ACCT>"
        const val DATE = "<DATE>"
        const val M = "<M>"
        const val N = "<N>"
        const val NAME = "<NAME>"
        const val ANY = "<ANY>"
        val SLOTS = setOf(AMT, BAL, ACCT, DATE, M, N, NAME, ANY)
        /** A slot inside a token (`UPI/<M>.`). */
        val SLOT = Regex("<(AMT|BAL|ACCT|DATE|M|N|NAME|ANY)>")
    }
}

object MessageShapes {
    private val GREETING = Regex("\\b(Dear|Hi|Hello)\\s+([A-Za-z][A-Za-z.]*)", RegexOption.IGNORE_CASE)
    private val DIGITS = Regex("[0-9]+")

    private data class Slot(val range: IntRange, val label: String)

    /**
     * The shape of [redacted] (a [dev.adminos.api.domain.ingestion.fallback.RedactedMessage.Ready] text) given what was
     * read from it, or null when the reading can't be located in the text (then nothing is learnt from it).
     */
    fun of(redacted: String, answer: MessageAnswer.Transaction): MessageShape? {
        val slots = mutableListOf<Slot>()
        answer.merchant?.let { m ->
            var at = redacted.indexOf(m, ignoreCase = true)
            while (at >= 0) {
                slots += Slot(at until at + m.length, MessageShape.M)
                at = redacted.indexOf(m, at + m.length, ignoreCase = true)
            }
            if (slots.isEmpty()) return null
        }
        val money = MessageGrounding.moneySpans(redacted)
        if (money.none { !it.mention.isBalance && it.mention.amount.compareTo(answer.amount) == 0 }) return null
        money.forEach { s ->
            val moved = !s.mention.isBalance && s.mention.amount.compareTo(answer.amount) == 0
            slots += Slot(s.number, if (moved) MessageShape.AMT else MessageShape.BAL)
        }
        MessageGrounding.dateSpans(redacted).forEach { slots += Slot(it.range, MessageShape.DATE) }
        MessageGrounding.lastFourSpans(redacted).forEach { r ->
            slots += Slot(r, if (redacted.substring(r) == answer.accountLast4) MessageShape.ACCT else MessageShape.N)
        }
        GREETING.findAll(redacted).forEach { m -> m.groups[2]?.let { slots += Slot(it.range, MessageShape.NAME) } }

        // Earlier slots win where two overlap (a merchant over a number in it, an amount over a date-like figure).
        val kept = mutableListOf<Slot>()
        for (s in slots.sortedWith(compareBy({ it.range.first }, { priority(it.label) }))) {
            if (kept.none { it.range.first <= s.range.last && s.range.first <= it.range.last }) kept += s
        }
        val out = StringBuilder()
        var at = 0
        for (s in kept.sortedBy { it.range.first }) {
            out.append(redacted, at, s.range.first).append(s.label)
            at = s.range.last + 1
        }
        out.append(redacted, at, redacted.length)
        val text = DIGITS.replace(out.toString(), MessageShape.N).replace(Regex("\\s+"), " ").trim()
        return text.takeIf { it.length <= MessageShape.MAX_LENGTH && MessageShape.AMT in it }?.let(::MessageShape)
    }

    private fun priority(label: String) = when (label) {
        MessageShape.M -> 0
        MessageShape.AMT, MessageShape.BAL -> 1
        MessageShape.DATE -> 2
        else -> 3
    }

    /** Two shapes of one format may differ in this share of their words at most (Drain's similarity threshold). */
    const val MAX_DIFFERENT_SHARE = 0.25

    /**
     * Drain-style merge (He et al., 2017): two shapes with the same number of words, where every differing word is
     * plain wording (never a slot) and at most [MAX_DIFFERENT_SHARE] of the words differ, are one format; the
     * differing words become [MessageShape.ANY]. Null when they are different formats.
     */
    fun merge(a: MessageShape, b: MessageShape): MessageShape? {
        if (a == b) return a
        val x = a.tokens
        val y = b.tokens
        if (x.size != y.size) return null
        val differ = x.indices.filter { x[it] != y[it] }
        if (differ.any { MessageShape.SLOT.containsMatchIn(x[it]) && x[it] != MessageShape.ANY || MessageShape.SLOT.containsMatchIn(y[it]) && y[it] != MessageShape.ANY }) return null
        if (differ.size > x.size * MAX_DIFFERENT_SHARE) return null
        return MessageShape(x.indices.joinToString(" ") { if (it in differ) MessageShape.ANY else x[it] })
    }
}
