package dev.adminos.api.domain.ingestion.cash

import java.math.BigDecimal

/**
 * The deterministic cash-phrase grammar (AOS-718, D-77). Steps, each pure:
 * 1. normalize ([PhraseText]): lower case, digits, number words, grouping commas;
 * 2. split into segments on `,` `;` `+` `&` "and" "aur" "plus" and line breaks;
 * 3. take each segment's date phrase out ([PhraseDates]); one date in the whole text applies to every segment;
 * 4. drop times ("at 5pm", "9 baje"), which aren't amounts;
 * 5. read amounts (₹/rs/inr, k/thousand/lakh, "/-", "15 x 2") and words; a small bare number before a word that is
 *    followed by another amount is a quantity ("2 samosa 30");
 * 6. pair amounts with words: "200 chai 150 auto" (amount first) or "chai 40 auto 120" (words first);
 * 7. the note is the words without filler ("paid", "for", "ka"); the category comes from [CashCategoryGuesser].
 * Anything it can't use is returned as an [UnparsedSegment] with the reason; nothing is guessed.
 */
class RuleCashPhraseParser(private val categories: CashCategoryGuesser) : CashPhraseParser {

    override suspend fun parse(text: String, context: PhraseContext): PhraseParse {
        val segments = split(PhraseText.normalize(text)).map { PhraseDates.extract(it, context.today) }
        val written = segments.mapNotNull { it.finding }
        val shared = written.singleOrNull()
        val entries = mutableListOf<DraftEntry>()
        val unparsed = mutableListOf<UnparsedSegment>()
        for (segment in segments) {
            val finding = segment.finding ?: shared ?: DateFinding.On(context.today)
            for (group in groups(segment.rest)) {
                when (val read = read(group, finding, context, segment.rest)) {
                    is GroupResult.Entry -> entries += read.entry
                    is GroupResult.Skipped -> unparsed += read.segment
                    GroupResult.Nothing -> Unit
                }
            }
        }
        return PhraseParse(entries, unparsed)
    }

    // ── Tokens ──

    private sealed interface Token {
        val raw: String
        /** [counted]: written as a price times a count ("15 x 2"), so the value is the product. */
        data class Amount(override val raw: String, val value: BigDecimal, val negative: Boolean, val marked: Boolean, val counted: Boolean = false) : Token
        data class Word(override val raw: String) : Token
    }

    /** A run of tokens with at most one amount, or words only. */
    private data class Group(val tokens: List<Token>, val quantity: Boolean)

    private sealed interface GroupResult {
        data class Entry(val entry: DraftEntry) : GroupResult
        data class Skipped(val segment: UnparsedSegment) : GroupResult
        data object Nothing : GroupResult
    }

    private fun tokens(segment: String): List<Token> = TOKEN.findAll(TIME.replace(segment, " ")).map { m ->
        val num = m.groups["num"]?.value
        if (num == null) Token.Word(m.value) else {
            val multiplier = when (m.groups["mul"]?.value?.take(2)) {
                null -> BigDecimal.ONE
                "hu" -> BigDecimal(100)
                "k", "th" -> BigDecimal(1_000)
                "la" -> BigDecimal(100_000)
                else -> BigDecimal(10_000_000) // crore
            }
            val times = m.groups["times"]?.value?.let(::BigDecimal)
            val marked = m.groups["cur"] != null || m.groups["cur2"] != null || m.groups["mul"] != null || times != null
            Token.Amount(m.value.trim(), BigDecimal(num).multiply(multiplier).multiply(times ?: BigDecimal.ONE),
                negative = m.groups["neg"] != null, marked = marked, counted = times != null)
        }
    }.toList()

    /** A small bare whole number before a word, with another amount later, is how many, not how much. */
    private fun withQuantities(tokens: List<Token>): Pair<List<Token>, Set<Int>> {
        val quantities = mutableSetOf<Int>()
        val out = tokens.mapIndexed { i, t ->
            val isQuantity = t is Token.Amount && !t.marked && !t.negative && t.value.stripTrailingZeros().scale() <= 0 &&
                t.value >= BigDecimal.ONE && t.value <= MAX_QUANTITY && tokens.getOrNull(i + 1) is Token.Word &&
                tokens.drop(i + 2).any { it is Token.Amount }
            if (isQuantity) { quantities += i; Token.Word(t.raw) } else t
        }
        return out to quantities
    }

    /** Pairs each amount with its words: amount first ("200 chai 150 auto") or words first ("chai 40 auto 120"). */
    private fun groups(segment: String): List<Group> {
        val (tokens, quantities) = withQuantities(tokens(segment))
        if (tokens.isEmpty()) return emptyList()
        if (tokens.none { it is Token.Amount }) return listOf(Group(tokens, quantity = false))
        val indexed = tokens.withIndex().toList()
        val parts = if (tokens.first() is Token.Amount) startingAtAmounts(indexed) else endingAtAmounts(indexed)
        return parts.map { g -> Group(g.map { it.value }, quantity = g.any { it.index in quantities || (it.value as? Token.Amount)?.counted == true }) }
    }

    /** "200 chai 150 auto": each amount starts an entry. */
    private fun startingAtAmounts(tokens: List<IndexedValue<Token>>): List<List<IndexedValue<Token>>> {
        val out = mutableListOf<MutableList<IndexedValue<Token>>>()
        for (t in tokens) if (t.value is Token.Amount) out.add(mutableListOf(t)) else out.last().add(t)
        return out
    }

    /** "chai 40 auto 120": each amount ends an entry; words after the last amount still belong to it. */
    private fun endingAtAmounts(tokens: List<IndexedValue<Token>>): List<List<IndexedValue<Token>>> {
        val out = mutableListOf<MutableList<IndexedValue<Token>>>(mutableListOf())
        for (t in tokens) {
            out.last().add(t)
            if (t.value is Token.Amount) out.add(mutableListOf())
        }
        val tail = out.removeAt(out.size - 1)
        out.last().addAll(tail)
        return out
    }

    private suspend fun read(group: Group, date: DateFinding, context: PhraseContext, segmentText: String): GroupResult {
        val evidence = group.tokens.joinToString(" ") { it.raw }
        val amount = group.tokens.filterIsInstance<Token.Amount>().singleOrNull()
        val words = group.tokens.filterIsInstance<Token.Word>().map { it.raw }.filterNot { it in FILLER }
        // Words without an amount: the whole segment (times included) is what the user or a fallback reads again.
        if (amount == null) return if (words.isEmpty()) GroupResult.Nothing else GroupResult.Skipped(UnparsedSegment(segmentText, UnparsedReason.NO_AMOUNT))
        invalid(amount)?.let { return GroupResult.Skipped(UnparsedSegment(evidence, it)) }
        val on = when (date) {
            DateFinding.Future -> return GroupResult.Skipped(UnparsedSegment(evidence, UnparsedReason.FUTURE_DATE))
            DateFinding.Invalid -> return GroupResult.Skipped(UnparsedSegment(evidence, UnparsedReason.INVALID_DATE))
            is DateFinding.On -> date
        }
        val note = words.joinToString(" ").takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() }?.take(CashIntake.MAX_NOTE)
        val (category, from) = categories.guess(context.userId, note)
        val warnings = buildSet {
            if (from == CategoryFrom.NONE) add(DraftWarning.CATEGORY_UNKNOWN)
            if (note == null) add(DraftWarning.NO_NOTE)
            if (on.assumed) add(DraftWarning.DATE_ASSUMED)
            if (group.quantity) add(DraftWarning.QUANTITY_ASSUMED)
            if (PhraseDates.isOld(on.date, context.today)) add(DraftWarning.OLD_DATE)
        }
        return GroupResult.Entry(DraftEntry(amount.value.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }, category, note, on.date, evidence, from, warnings))
    }

    private fun invalid(a: Token.Amount): UnparsedReason? = when {
        a.negative -> UnparsedReason.NEGATIVE_AMOUNT
        a.value.signum() == 0 -> UnparsedReason.ZERO_AMOUNT
        a.value > CashIntake.MAX_AMOUNT -> UnparsedReason.TOO_LARGE
        a.value.stripTrailingZeros().scale() > 2 -> UnparsedReason.TOO_PRECISE
        else -> null
    }

    companion object {
        private val MAX_QUANTITY = BigDecimal(12)

        private val SEPARATOR = Regex("\\s*(?:[,;|\\n+&]|\\.(?=\\s|$)|\\b(?:and|aur|plus|then|phir)\\b)\\s*")
        /** Times of day aren't amounts: "at 5pm", "9:30", "5 baje". */
        private val TIME = Regex("\\b(?:at\\s+)?\\d{1,2}(?::\\d{2})?\\s*(?:am|pm|baje)\\b|\\b(?:at\\s+)?\\d{1,2}:\\d{2}\\b")
        private val TOKEN = Regex(
            "(?<neg>(?<![\\p{L}\\p{N}])(?:-\\s*|minus\\s+))?(?<cur>(?:₹|rs|inr)\\s*)?(?<num>\\d+(?:\\.\\d+)?)" +
                "(?:\\s*(?<mul>k|hundred|thousand|lakhs?|lacs?|crores?|cr)\\b)?(?:\\s*(?<cur2>rupees?|rupaye|rupay|rs|inr|₹|/-))?" +
                "(?:\\s*[x×*]\\s*(?<times>\\d{1,4})\\b)?" +
                "|\\p{L}(?:[\\p{L}'-]*\\p{L})?"
        )

        /** Words that say nothing about what it was. */
        val FILLER = setOf(
            "paid", "pay", "payed", "spent", "spend", "gave", "given", "diye", "diya", "de", "di", "bought", "buy", "got", "kharcha",
            "kharch", "kharche", "kharida", "kharidi", "liya", "liye", "le", "li", "lo", "for", "on", "at", "of", "to", "the", "a", "an",
            "ka", "ke", "ki", "ko", "mein", "me", "se", "in", "rs", "rupees", "rupee", "rupaye", "rupay", "inr", "worth", "cash", "nagad",
            "total", "approx", "around", "about", "just", "only", "sirf", "bas", "hi", "ji", "morning", "evening", "night", "afternoon",
            "subah", "shaam", "raat", "dopahar", "my", "i", "we", "was", "were", "is", "with", "by", "hua", "hue", "hui", "tha", "thi"
        )

        fun split(text: String): List<String> = text.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
    }
}
