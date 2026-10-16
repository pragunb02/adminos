package dev.adminos.api.domain.financial

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CatalogueMerchant(
    val brand: String,
    val category: String,
    val subcategory: String? = null,
    val aliases: List<String>
)

@Serializable
private data class CatalogueFile(val version: String, val merchants: List<CatalogueMerchant>)

/**
 * The one list of known merchants (AOS-206): brand, the names banks print for it (incl. legal
 * entities like "BUNDL TECHNOLOGIES" → Swiggy), and its default category. Both the merchant
 * normalizer and the categorizer read it, so they can't drift apart. Data, not code:
 * `resources/merchants/catalogue.json`. Validated on load; a bad catalogue fails startup.
 */
class MerchantCatalogue(val version: String, val merchants: List<CatalogueMerchant>) {

    data class Entry(val brand: String, val category: TransactionCategory, val subcategory: String?)

    /** Alias in matching form (see [matchForm]) → brand. */
    val aliases: Map<String, String>
    private val byBrand: Map<String, Entry>

    init {
        val seen = mutableMapOf<String, String>()
        val entries = mutableMapOf<String, Entry>()
        for (m in merchants) {
            require(m.brand.isNotBlank()) { "merchant catalogue: blank brand" }
            val category = TransactionCategory.entries.firstOrNull { it.name.equals(m.category, ignoreCase = true) }
                ?: throw IllegalArgumentException("merchant catalogue: ${m.brand} has unknown category '${m.category}'")
            require(entries.put(m.brand.lowercase(), Entry(m.brand, category, m.subcategory)) == null) { "merchant catalogue: duplicate brand ${m.brand}" }
            require(m.aliases.isNotEmpty()) { "merchant catalogue: ${m.brand} has no aliases" }
            for (a in m.aliases) {
                val key = matchForm(a)
                require(key.length >= 3) { "merchant catalogue: alias '$a' of ${m.brand} is too short to match safely" }
                val other = seen.put(key, m.brand)
                require(other == null || other == m.brand) { "merchant catalogue: alias '$a' belongs to both $other and ${m.brand}" }
            }
        }
        aliases = seen
        byBrand = entries
    }

    /** The catalogue entry for a normalized brand name, case-insensitive. */
    fun byBrand(name: String?): Entry? = name?.trim()?.lowercase()?.let { byBrand[it] }

    companion object {
        /** Upper case, every run of non-alphanumerics → one space: "CULT.FIT" and "Cult Fit" match alike. */
        fun matchForm(s: String): String = s.uppercase().replace(Regex("[^A-Z0-9]+"), " ").trim()

        val DEFAULT: MerchantCatalogue by lazy { load("/merchants/catalogue.json") }

        fun load(resource: String): MerchantCatalogue {
            val text = MerchantCatalogue::class.java.getResource(resource)?.readText()
                ?: throw IllegalStateException("merchant catalogue not found: $resource")
            val file = Json.decodeFromString<CatalogueFile>(text)
            return MerchantCatalogue(file.version, file.merchants)
        }
    }
}
