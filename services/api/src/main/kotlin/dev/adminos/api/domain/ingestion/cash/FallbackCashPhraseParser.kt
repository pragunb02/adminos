package dev.adminos.api.domain.ingestion.cash

import dev.adminos.api.domain.financial.TransactionCategory
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * The extension point for a later AI reader (AOS-718 → AOS-702's `AiProvider`, D-67). The rules go first; the
 * fallback only sees what they left open, and its answers are checked against the text:
 * - an unparsed segment ("teen sau ka petrol", odd phrasing): the fallback may propose entries, kept only when
 *   every amount's digits appear in that segment ([grounded]); otherwise the segment stays unparsed;
 * - an entry the rules read but couldn't categorise ([CategoryFrom.NONE]): the fallback may propose a category for
 *   the same amount and date; never Transfer.
 * What the rules decided is never changed (D-61/D-62). Nothing here is wired yet: the API uses [RuleCashPhraseParser]
 * alone until an AI parser exists behind the AI layer's consent toggle and eval gate.
 */
class FallbackCashPhraseParser(private val rules: CashPhraseParser, private val fallback: CashPhraseParser) : CashPhraseParser {
    private val log = LoggerFactory.getLogger(FallbackCashPhraseParser::class.java)

    override suspend fun parse(text: String, context: PhraseContext): PhraseParse {
        val first = rules.parse(text, context)
        val entries = first.entries.map { e -> if (e.categoryFrom == CategoryFrom.NONE) recategorised(e, context) else e }
        val unparsed = mutableListOf<UnparsedSegment>()
        val added = mutableListOf<DraftEntry>()
        for (segment in first.unparsed) {
            // Dates and amounts the rules refused stay refused; only "no amount" text is worth a second reading.
            if (segment.reason != UnparsedReason.NO_AMOUNT) { unparsed += segment; continue }
            val proposed = fallback.parse(segment.text, context).entries
            val kept = proposed.filter { grounded(it, segment.text) && it.category != TransactionCategory.TRANSFER }
            if (kept.size < proposed.size) log.info("Cash fallback: {} of {} proposals rejected (not grounded in the text)", proposed.size - kept.size, proposed.size)
            if (kept.isEmpty()) unparsed += segment else added += kept.map { it.copy(categoryFrom = CategoryFrom.FALLBACK, evidence = segment.text) }
        }
        return PhraseParse(entries + added, unparsed)
    }

    /** Only the category of an entry the rules left as Other may change, and only to a proposal for the same amount and date. */
    private suspend fun recategorised(e: DraftEntry, context: PhraseContext): DraftEntry {
        val proposal = fallback.parse(e.evidence, context).entries.firstOrNull { it.amount.compareTo(e.amount) == 0 && it.date == e.date }
            ?: return e
        if (proposal.category == TransactionCategory.TRANSFER || proposal.category == TransactionCategory.OTHER) return e
        return e.copy(category = proposal.category, categoryFrom = CategoryFrom.FALLBACK, warnings = e.warnings - DraftWarning.CATEGORY_UNKNOWN)
    }

    companion object {
        /** Whether [e]'s amount is written in [text] as digits (D-67: a model may extract a number, never invent one). */
        fun grounded(e: DraftEntry, text: String): Boolean {
            val digits = PhraseText.normalize(text)
            val plain = e.amount.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
            val forms = listOf(plain, e.amount.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString()) +
                thousands(e.amount)
            return forms.any { Regex("(?<![\\d.])" + Regex.escape(it) + "(?![\\d])").containsMatchIn(digits) }
        }

        /** 1200 can be written 1.2k. */
        private fun thousands(amount: BigDecimal): List<String> {
            val k = amount.divide(BigDecimal(1000)).stripTrailingZeros()
            return if (k.scale() in 0..3 && k.signum() > 0) listOf("${k.toPlainString()} k", "${k.toPlainString()}k") else emptyList()
        }
    }
}
