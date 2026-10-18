package dev.adminos.api.domain.ingestion.cash

import dev.adminos.api.domain.financial.TransactionCategory
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Whose words, and what "today" is for them (their own zone, AOS-120). */
data class PhraseContext(val userId: UUID, val today: LocalDate)

/** Where an entry's category came from, so the preview can say why (and a fallback knows what the rules decided). */
enum class CategoryFrom {
    /** The user's own correction for this payee (D-41). */
    REMEMBERED,
    /** A brand in the merchant catalogue (D-44). */
    CATALOGUE,
    /** A cash keyword: "chai", "auto", "sabzi" (`resources/cash/keywords.json`). */
    KEYWORD,
    /** The categorizer's whole-word patterns. */
    PATTERN,
    /** Nothing matched: Other, for the user to pick. */
    NONE,
    /** A fallback parser suggested it (AI later, AOS-702); never used for what the rules decided. */
    FALLBACK
}

/** Something the user should look at before saving. */
enum class DraftWarning {
    /** No category matched; it's Other until the user picks one. */
    CATEGORY_UNKNOWN,
    /** "kal" or "parso" can mean either way; the past was assumed (cash is logged after it's spent). */
    DATE_ASSUMED,
    /** A small number before a word was read as a quantity ("2 samosa 30"), not an amount. */
    QUANTITY_ASSUMED,
    /** The date is more than a year ago. */
    OLD_DATE,
    /** There's no word for what it was. */
    NO_NOTE
}

/**
 * One cash entry read from the text, for the user to confirm or edit. [evidence] is the exact part of the
 * input it came from; its amount always appears there (D-67 grounding).
 */
data class DraftEntry(
    val amount: BigDecimal,
    val category: TransactionCategory,
    val note: String?,
    val date: LocalDate,
    val evidence: String,
    val categoryFrom: CategoryFrom,
    val warnings: Set<DraftWarning> = emptySet()
) {
    init {
        require(amount.signum() > 0) { "A cash entry is a positive amount" }
        require(amount <= CashIntake.MAX_AMOUNT) { "A cash entry is at most ${CashIntake.MAX_AMOUNT}" }
        require(amount.stripTrailingZeros().scale() <= 2) { "A cash entry has at most 2 decimal places" }
        require(evidence.isNotBlank()) { "A draft entry names the text it came from" }
    }
}

/** Why part of the text gave no entry. */
enum class UnparsedReason {
    /** No amount in it ("chai"). */
    NO_AMOUNT,
    ZERO_AMOUNT,
    NEGATIVE_AMOUNT,
    /** Over ₹1 crore: a typo, as in [CashIntake]. */
    TOO_LARGE,
    /** More than two decimal places. */
    TOO_PRECISE,
    /** "tomorrow" or a date after today: cash is logged once it's spent. */
    FUTURE_DATE,
    /** A date that doesn't exist (31/02). */
    INVALID_DATE
}

data class UnparsedSegment(val text: String, val reason: UnparsedReason)

/** What a parser made of the text: entries to preview, and the parts it couldn't use, with why. */
data class PhraseParse(val entries: List<DraftEntry>, val unparsed: List<UnparsedSegment>) {
    companion object {
        val EMPTY = PhraseParse(emptyList(), emptyList())
    }
}

/**
 * Reads free text ("200 chai and 150 auto", "paid 1.2k for groceries yesterday") into cash entries to
 * preview (AOS-718). Nothing is saved: the user confirms each entry, which then goes through [CashIntake].
 * Implementations: [RuleCashPhraseParser] (deterministic, shipped) and [FallbackCashPhraseParser], which
 * lets a later parser (an AI one through AOS-702's AiProvider) fill only what the rules left open.
 */
interface CashPhraseParser {
    suspend fun parse(text: String, context: PhraseContext): PhraseParse

    companion object {
        const val MAX_TEXT = 500
    }
}
