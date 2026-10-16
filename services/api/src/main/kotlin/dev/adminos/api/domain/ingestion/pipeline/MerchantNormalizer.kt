package dev.adminos.api.domain.ingestion.pipeline

/**
 * Turns raw merchant strings and UPI VPAs into one consistent brand name.
 *
 * Deterministic, unlike the Go version it replaces (random map order):
 * - aliases match **whole words**, so "COCA COLA" is not Ola;
 * - **longest alias wins**, so "UBER EATS" is Uber Eats, not Uber.
 * Unknown merchants are title-cased. The alias catalogue grows in AOS-206.
 */
class MerchantNormalizer(aliases: Map<String, String> = DEFAULT_ALIASES) {

    private val matchers: List<Pair<Regex, String>> = aliases.entries
        .sortedByDescending { it.key.length }
        .map { (alias, brand) -> Regex("(?<![A-Z0-9])" + Regex.escape(alias.uppercase()) + "(?![A-Z0-9])") to brand }

    fun normalize(raw: String?, upiVpa: String? = null): String? {
        val candidates = listOfNotNull(raw, upiVpa?.substringBefore('@')?.replace('.', ' ')?.replace('-', ' '))
        for (text in candidates) {
            val upper = text.uppercase()
            matchers.firstOrNull { (re, _) -> re.containsMatchIn(upper) }?.let { return it.second }
        }
        return raw?.takeIf { it.isNotBlank() }?.let(::titleCase)
    }

    private fun titleCase(s: String): String =
        s.trim().lowercase().split(Regex("\\s+")).joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    companion object {
        val DEFAULT_ALIASES: Map<String, String> = mapOf(
            "ZOMATO" to "Zomato", "SWIGGY" to "Swiggy", "UBER EATS" to "Uber Eats", "UBER" to "Uber",
            "OLA" to "Ola", "RAPIDO" to "Rapido", "AMAZON PRIME" to "Amazon Prime", "AMAZON" to "Amazon",
            "AMZN" to "Amazon", "FLIPKART" to "Flipkart", "MYNTRA" to "Myntra", "BIGBASKET" to "BigBasket",
            "NETFLIX" to "Netflix", "SPOTIFY" to "Spotify", "HOTSTAR" to "Hotstar", "YOUTUBE PREMIUM" to "YouTube Premium",
            "PAYTM" to "Paytm", "PHONEPE" to "PhonePe", "GPAY" to "Google Pay", "GOOGLE PAY" to "Google Pay",
            "IRCTC" to "IRCTC", "JIO" to "Jio", "AIRTEL" to "Airtel", "STARBUCKS" to "Starbucks", "DOMINOS" to "Domino's"
        )
    }
}
