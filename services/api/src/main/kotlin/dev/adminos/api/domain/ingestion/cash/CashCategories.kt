package dev.adminos.api.domain.ingestion.cash

import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.CategoryRuleRepository
import dev.adminos.api.domain.financial.MerchantCatalogue
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.ingestion.pipeline.MerchantNormalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
private data class KeywordRuleFile(val category: String, val subcategory: String? = null, val words: List<String>)

@Serializable
private data class KeywordFile(val version: String, val comment: String? = null, val rules: List<KeywordRuleFile>)

/**
 * The words people use for cash spends, English and Hinglish ("chai", "auto", "sabzi"), as data
 * (`resources/cash/keywords.json`, like the merchant catalogue, D-44). Validated on load: a bad file fails startup.
 * Matching is by whole words; the longest phrase wins ("pani puri" is food, "pani" alone is water).
 */
class CashKeywords private constructor(val version: String, private val phrases: List<Pair<List<String>, TransactionCategory>>) {

    /** The category of the longest keyword phrase in [note] (earliest on a tie), or null. */
    fun match(note: String): TransactionCategory? {
        val words = wordsOf(note)
        if (words.isEmpty()) return null
        return phrases.asSequence()
            .mapNotNull { (phrase, category) -> indexOf(words, phrase)?.let { Triple(phrase.size, it, category) } }
            .sortedWith(compareByDescending<Triple<Int, Int, TransactionCategory>> { it.first }.thenBy { it.second })
            .firstOrNull()?.third
    }

    companion object {
        val DEFAULT: CashKeywords by lazy { load("/cash/keywords.json") }

        fun load(resource: String): CashKeywords {
            val text = CashKeywords::class.java.getResource(resource)?.readText() ?: throw IllegalStateException("cash keywords not found: $resource")
            return parse(text)
        }

        fun parse(json: String): CashKeywords {
            val file = Json.decodeFromString<KeywordFile>(json)
            val seen = mutableMapOf<String, TransactionCategory>()
            val phrases = file.rules.flatMap { rule ->
                val category = TransactionCategory.entries.firstOrNull { it.name.equals(rule.category, ignoreCase = true) }
                    ?: throw IllegalArgumentException("cash keywords: unknown category '${rule.category}'")
                require(category != TransactionCategory.TRANSFER) { "cash keywords: cash spending is never a transfer (D-42)" }
                require(rule.words.isNotEmpty()) { "cash keywords: ${rule.category} has no words" }
                rule.words.map { w ->
                    val phrase = wordsOf(w)
                    require(phrase.joinToString("").length >= 2) { "cash keywords: '$w' is too short to match safely" }
                    val other = seen.put(phrase.joinToString(" "), category)
                    require(other == null) { "cash keywords: '$w' is listed twice" }
                    phrase to category
                }
            }
            return CashKeywords(file.version, phrases)
        }

        /** Lower case words of letters and digits; "t-shirt" is "t shirt". */
        fun wordsOf(s: String): List<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

        private fun indexOf(words: List<String>, phrase: List<String>): Int? =
            (0..words.size - phrase.size).firstOrNull { i -> phrase.indices.all { words[i + it] == phrase[it] } }
    }
}

/**
 * Picks a cash entry's category from its note (AOS-718), in the categorizer's order (D-41), with cash
 * words added before the generic patterns:
 * 1. the user's remembered rule for this payee (the note becomes the merchant name in the pipeline);
 * 2. a catalogue brand ("Swiggy", "Dominos"), unless it's a payment app (Other);
 * 3. a cash keyword ("chai", "auto", "sabzi");
 * 4. the categorizer's whole-word patterns;
 * 5. Other, which the preview flags for the user to pick.
 * Never Transfer: cash already left the bank (D-42).
 */
class CashCategoryGuesser(
    private val rules: CategoryRuleRepository,
    private val keywords: CashKeywords = CashKeywords.DEFAULT,
    private val catalogue: MerchantCatalogue = MerchantCatalogue.DEFAULT
) {
    private val normalizer = MerchantNormalizer(catalogue)
    private val categorizer = CategorizationService(rules, catalogue)

    suspend fun guess(userId: UUID, note: String?): Pair<TransactionCategory, CategoryFrom> {
        val text = note?.trim()?.takeIf { it.isNotEmpty() } ?: return TransactionCategory.OTHER to CategoryFrom.NONE
        val merchant = normalizer.normalize(text) ?: text
        rules.findFor(userId, null, merchant.trim().lowercase())?.let { return usable(it.category, CategoryFrom.REMEMBERED) }
        // A payment app in the catalogue (Paytm, GPay: Other) says how it was paid, not what for.
        catalogue.byBrand(merchant)?.takeIf { it.category != TransactionCategory.OTHER }?.let { return usable(it.category, CategoryFrom.CATALOGUE) }
        keywords.match(text)?.let { return it to CategoryFrom.KEYWORD }
        val pattern = categorizer.builtIn(merchant).category
        return if (pattern == TransactionCategory.OTHER) TransactionCategory.OTHER to CategoryFrom.NONE else usable(pattern, CategoryFrom.PATTERN)
    }

    private fun usable(category: TransactionCategory, from: CategoryFrom): Pair<TransactionCategory, CategoryFrom> =
        if (category == TransactionCategory.TRANSFER) TransactionCategory.OTHER to CategoryFrom.NONE else category to from
}
