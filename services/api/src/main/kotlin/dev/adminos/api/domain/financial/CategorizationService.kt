package dev.adminos.api.domain.financial

import org.slf4j.LoggerFactory

/**
 * Rules-based transaction categorizer.
 * 1. Known merchant DB lookup (exact match → category + subcategory, confidence 0.95)
 * 2. Pattern-based rules for Indian merchants
 * 3. AI fallback enqueue when confidence < 0.7
 */
class CategorizationService {

    private val logger = LoggerFactory.getLogger(CategorizationService::class.java)

    // Known merchant database: normalized name → (category, subcategory)
    private val knownMerchants: Map<String, Pair<TransactionCategory, String?>> = mapOf(
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
        "paytm" to (TransactionCategory.TRANSFER to "wallet"),
        "phonepe" to (TransactionCategory.TRANSFER to "upi"),
        "google pay" to (TransactionCategory.TRANSFER to "upi"),
    )

    // Pattern-based rules: regex → (category, confidence)
    private val patternRules: List<Triple<Regex, TransactionCategory, Double>> = listOf(
        Triple(Regex("zomato|swiggy|uber eats|food|restaurant|cafe|pizza|burger", RegexOption.IGNORE_CASE), TransactionCategory.FOOD, 0.9),
        Triple(Regex("uber|ola|rapido|metro|irctc|railway|cab|taxi", RegexOption.IGNORE_CASE), TransactionCategory.TRANSPORT, 0.9),
        Triple(Regex("netflix|hotstar|spotify|prime video|youtube premium|disney|jiocinema", RegexOption.IGNORE_CASE), TransactionCategory.SUBSCRIPTION, 0.9),
        Triple(Regex("amazon|flipkart|myntra|ajio|meesho|nykaa|bigbasket", RegexOption.IGNORE_CASE), TransactionCategory.SHOPPING, 0.85),
        Triple(Regex("emi|loan|equated|bajaj finserv|hdfc loan", RegexOption.IGNORE_CASE), TransactionCategory.EMI, 0.85),
        Triple(Regex("electricity|water|gas|broadband|internet|jio fiber|airtel fiber", RegexOption.IGNORE_CASE), TransactionCategory.UTILITIES, 0.85),
        Triple(Regex("atm|cash|withdrawal|neft|imps|rtgs", RegexOption.IGNORE_CASE), TransactionCategory.TRANSFER, 0.8),
    )

    // AI fallback job queue (in-memory for MVP)
    private val aiFallbackQueue = mutableListOf<java.util.UUID>()

    fun categorize(merchantName: String?, paymentMethod: PaymentMethod? = null, transactionId: java.util.UUID? = null): CategorizationResult {
        val normalized = merchantName?.trim()?.lowercase() ?: ""

        // Step 1: Exact match in known merchant DB
        knownMerchants[normalized]?.let { (category, subcategory) ->
            return CategorizationResult(
                category = category,
                subcategory = subcategory,
                confidence = 0.95,
                source = "rules"
            )
        }

        // Step 2: Pattern-based rules
        for ((pattern, category, confidence) in patternRules) {
            if (pattern.containsMatchIn(normalized)) {
                return CategorizationResult(
                    category = category,
                    subcategory = null,
                    confidence = confidence,
                    source = "rules"
                )
            }
        }

        // Step 2b: UPI transfer detection
        if (paymentMethod == PaymentMethod.UPI) {
            return CategorizationResult(
                category = TransactionCategory.TRANSFER,
                subcategory = "upi",
                confidence = 0.75,
                source = "rules"
            )
        }

        // Step 3: No match → AI fallback
        transactionId?.let {
            aiFallbackQueue.add(it)
            logger.info("Enqueued AI categorization fallback for transaction {}", it)
        }

        return CategorizationResult(
            category = TransactionCategory.OTHER,
            subcategory = null,
            confidence = 0.3,
            source = "rules"
        )
    }

    fun getAiFallbackQueue(): List<java.util.UUID> = aiFallbackQueue.toList()

    fun clearAiFallbackQueue() { aiFallbackQueue.clear() }
}
