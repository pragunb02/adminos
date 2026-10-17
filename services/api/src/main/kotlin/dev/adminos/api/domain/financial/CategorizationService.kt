package dev.adminos.api.domain.financial

/**
 * The single categorizer (AOS-210, D-41), every source goes through it via the pipeline.
 * A chain, first match wins:
 * 1. the user's remembered rule for this payee (UPI ID, else merchant name)
 * 2. the merchant catalogue (AOS-206): the brand the normalizer produced → its default category
 * 3. whole-word patterns
 * 4. Other
 * Transfers are not guessed here: own-account transfers come from TransferStep (D-39), which runs after.
 */
class CategorizationService(
    private val rules: CategoryRuleRepository = InMemoryCategoryRuleRepository(),
    private val catalogue: MerchantCatalogue = MerchantCatalogue.DEFAULT
) {

    suspend fun categorize(t: Transaction): CategorizationResult {
        val vpa = t.upiVpa?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val merchant = t.merchantName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (vpa != null || merchant != null) rules.findFor(t.userId, vpa, merchant)?.let {
            return CategorizationResult(it.category, it.subcategory, confidence = 1.0, source = SOURCE_REMEMBERED)
        }
        return builtIn(t.merchantName)
    }

    /** Steps 2–4: no user state, so usable anywhere. */
    fun builtIn(merchantName: String?): CategorizationResult {
        catalogue.byBrand(merchantName)?.let {
            return CategorizationResult(it.category, it.subcategory, confidence = 0.95)
        }
        val normalized = merchantName?.trim()?.lowercase().orEmpty()
        PATTERNS.firstOrNull { it.pattern.containsMatchIn(normalized) }?.let {
            return CategorizationResult(it.category, it.subcategory, confidence = it.confidence)
        }
        return CategorizationResult(TransactionCategory.OTHER, confidence = 0.3)
    }

    private data class Pattern(val pattern: Regex, val category: TransactionCategory, val confidence: Double, val subcategory: String? = null)

    companion object {
        const val SOURCE_REMEMBERED = "remembered"

        private fun words(vararg w: String) = Regex("\\b(${w.joinToString("|")})\\b", RegexOption.IGNORE_CASE)

        // Whole words only: "ola" must not match "Motorola", nor "emi" match "premium".
        private val PATTERNS = listOf(
            Pattern(words("zomato", "swiggy", "uber eats", "food", "foods", "restaurant", "cafe", "pizza", "burger", "bakery", "sweets"), TransactionCategory.FOOD, 0.9),
            // Fuel, health and education words, and parking: gaps the AOS-511 golden set found.
            Pattern(words("petrol", "diesel", "fuel", "fuels", "filling station", "petroleum"), TransactionCategory.TRANSPORT, 0.85, "fuel"),
            Pattern(words("uber", "ola", "rapido", "metro", "irctc", "railway", "cab", "taxi", "parking"), TransactionCategory.TRANSPORT, 0.9),
            Pattern(words("pharmacy", "medical", "medicals", "chemist", "chemists"), TransactionCategory.HEALTH, 0.85, "pharmacy"),
            Pattern(words("hospital", "hospitals", "clinic", "dental", "diagnostics", "pathlabs", "gym"), TransactionCategory.HEALTH, 0.85),
            Pattern(words("school", "university", "college", "tuition", "coaching"), TransactionCategory.EDUCATION, 0.85),
            Pattern(words("netflix", "hotstar", "spotify", "prime video", "youtube premium", "disney", "jiocinema"), TransactionCategory.SUBSCRIPTION, 0.9),
            Pattern(words("amazon", "flipkart", "myntra", "ajio", "meesho", "nykaa", "bigbasket"), TransactionCategory.SHOPPING, 0.85),
            Pattern(words("emi", "loan", "equated", "bajaj finserv"), TransactionCategory.EMI, 0.85),
            Pattern(words("electricity", "water", "gas", "broadband", "internet", "jio fiber", "airtel fiber"), TransactionCategory.UTILITIES, 0.85),
            Pattern(words("atm", "cash withdrawal"), TransactionCategory.TRANSFER, 0.8, "cash"),
        )
    }
}
