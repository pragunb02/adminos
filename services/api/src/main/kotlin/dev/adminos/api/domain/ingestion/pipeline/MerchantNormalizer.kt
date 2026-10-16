package dev.adminos.api.domain.ingestion.pipeline

import dev.adminos.api.domain.financial.MerchantCatalogue
import dev.adminos.api.domain.financial.MerchantCatalogue.Companion.matchForm

/**
 * Turns raw merchant strings and UPI VPAs into one consistent name (AOS-201, AOS-206).
 * 1. A known brand from the [MerchantCatalogue], found in the narration or the UPI handle:
 *    aliases match **whole words** ("COCA COLA" is not Ola) and the **longest alias wins**
 *    ("UBER EATS" is Uber Eats, not Uber). Punctuation is ignored ("CULT.FIT" = "CULT FIT").
 * 2. Otherwise the payee name pulled out of the narration by [NarrationCleaner], title-cased.
 * 3. Otherwise the UPI handle as a name ("ramu.chai@ybl" → "Ramu Chai").
 * Deterministic, so the same narration always gives the same name — remembered rules depend on it.
 */
class MerchantNormalizer(catalogue: MerchantCatalogue = MerchantCatalogue.DEFAULT) {

    private val matchers: List<Pair<Regex, String>> = catalogue.aliases.entries
        .sortedByDescending { it.key.length }
        .map { (alias, brand) -> Regex("(?<![A-Z0-9])" + Regex.escape(alias) + "(?![A-Z0-9])") to brand }

    fun normalize(raw: String?, upiVpa: String? = null): String? {
        val handle = upiVpa?.substringBefore('@')?.takeIf { it.isNotBlank() }
        for (text in listOfNotNull(raw, handle)) {
            val form = matchForm(text)
            matchers.firstOrNull { (re, _) -> re.containsMatchIn(form) }?.let { return it.second }
        }
        NarrationCleaner.clean(raw)?.let { return titleCase(it) }
        return handle?.let { h -> matchForm(h).replace(Regex("\\d+"), " ").trim().takeIf { it.length >= 2 }?.let(::titleCase) }
    }

    private fun titleCase(s: String): String =
        s.trim().lowercase().split(Regex("\\s+")).joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}
