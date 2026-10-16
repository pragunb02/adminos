package dev.adminos.api.domain.financial

/**
 * The single categorizer (AOS-210, D-41), every source goes through it via the pipeline.
 * A chain, first match wins:
 * 1. the user's remembered rule for this payee (UPI ID, else merchant name)
 * 2. the built-in merchant list (exact normalized name)
 * 3. whole-word patterns
 * 4. Other
 * Transfers are not guessed here: own-account transfers come from TransferStep (D-39), which runs after.
 */
class CategorizationService(private val rules: CategoryRuleRepository = InMemoryCategoryRuleRepository()) {

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
        val normalized = merchantName?.trim()?.lowercase().orEmpty()
        KNOWN_MERCHANTS[normalized]?.let { (category, subcategory) ->
            return CategorizationResult(category, subcategory, confidence = 0.95)
        }
        PATTERNS.firstOrNull { it.pattern.containsMatchIn(normalized) }?.let {
            return CategorizationResult(it.category, it.subcategory, confidence = it.confidence)
        }
        return CategorizationResult(TransactionCategory.OTHER, confidence = 0.3)
    }

    private data class Pattern(val pattern: Regex, val category: TransactionCategory, val confidence: Double, val subcategory: String? = null)

    companion object {
        const val SOURCE_REMEMBERED = "remembered"

        private val KNOWN_MERCHANTS: Map<String, Pair<TransactionCategory, String?>> = mapOf(
            "zomato" to (TransactionCategory.FOOD to "food_delivery"),
            "swiggy" to (TransactionCategory.FOOD to "food_delivery"),
            "uber eats" to (TransactionCategory.FOOD to "food_delivery"),
            "dominos" to (TransactionCategory.FOOD to "restaurant"),
            "mcdonalds" to (TransactionCategory.FOOD to "restaurant"),
            "starbucks" to (TransactionCategory.FOOD to "cafe"),
            "uber" to (TransactionCategory.TRANSPORT to "ride_hailing"),
            "ola" to (TransactionCategory.TRANSPORT to "ride_hailing"),
            "rapido" to (TransactionCategory.TRANSPORT to "ride_hailing"),
            "netflix" to (TransactionCategory.SUBSCRIPTION to "streaming"),
            "hotstar" to (TransactionCategory.SUBSCRIPTION to "streaming"),
            "spotify" to (TransactionCategory.SUBSCRIPTION to "music"),
            "amazon prime" to (TransactionCategory.SUBSCRIPTION to "streaming"),
            "youtube premium" to (TransactionCategory.SUBSCRIPTION to "streaming"),
            "amazon" to (TransactionCategory.SHOPPING to "ecommerce"),
            "flipkart" to (TransactionCategory.SHOPPING to "ecommerce"),
            "myntra" to (TransactionCategory.SHOPPING to "fashion"),
            "bigbasket" to (TransactionCategory.SHOPPING to "groceries"),
            "jio" to (TransactionCategory.UTILITIES to "mobile"),
            "airtel" to (TransactionCategory.UTILITIES to "mobile"),
            "vodafone" to (TransactionCategory.UTILITIES to "mobile"),
            "bsnl" to (TransactionCategory.UTILITIES to "mobile"),
        )

        private fun words(vararg w: String) = Regex("\\b(${w.joinToString("|")})\\b", RegexOption.IGNORE_CASE)

        // Whole words only: "ola" must not match "Motorola", nor "emi" match "premium".
        private val PATTERNS = listOf(
            Pattern(words("zomato", "swiggy", "uber eats", "food", "restaurant", "cafe", "pizza", "burger"), TransactionCategory.FOOD, 0.9),
            Pattern(words("uber", "ola", "rapido", "metro", "irctc", "railway", "cab", "taxi"), TransactionCategory.TRANSPORT, 0.9),
            Pattern(words("netflix", "hotstar", "spotify", "prime video", "youtube premium", "disney", "jiocinema"), TransactionCategory.SUBSCRIPTION, 0.9),
            Pattern(words("amazon", "flipkart", "myntra", "ajio", "meesho", "nykaa", "bigbasket"), TransactionCategory.SHOPPING, 0.85),
            Pattern(words("emi", "loan", "equated", "bajaj finserv"), TransactionCategory.EMI, 0.85),
            Pattern(words("electricity", "water", "gas", "broadband", "internet", "jio fiber", "airtel fiber"), TransactionCategory.UTILITIES, 0.85),
            Pattern(words("atm", "cash withdrawal"), TransactionCategory.TRANSFER, 0.8, "cash"),
        )
    }
}
