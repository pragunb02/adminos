package dev.adminos.api.domain.coach.lenses

import dev.adminos.api.domain.financial.MerchantCatalogue
import dev.adminos.api.domain.financial.TransactionCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalTime
import java.time.format.DateTimeParseException

@Serializable
data class LensEntry(
    val id: String,
    val label: String,
    val description: String = "",
    val brands: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val subcategories: List<String> = emptyList(),
    val keywords: List<String> = emptyList()
)

@Serializable
data class LateNightEntry(val from: String, val to: String)

@Serializable
data class LensFile(val version: String, val lateNight: LateNightEntry, val lenses: List<LensEntry>)

/**
 * The default habit lenses (AOS-901, D-80): data in `resources/coach/lenses.json`, like the merchant
 * catalogue (D-44). Validated on load against that catalogue, so a typo in a brand, an unknown
 * category, a duplicate id or a too-short keyword fails startup instead of silently matching nothing.
 */
class LensCatalogue(file: LensFile, merchants: MerchantCatalogue = MerchantCatalogue.DEFAULT) {
    val version: String = file.version
    val lateNight: LateNightWindow = parseWindow(file.lateNight)
    val lenses: List<HabitLens>
    private val byId: Map<LensId, HabitLens>

    init {
        require(file.version.isNotBlank()) { "lens file: blank version" }
        require(file.lenses.isNotEmpty()) { "lens file: no lenses" }
        lenses = file.lenses.map { toLens(it, merchants) }
        byId = lenses.associateBy { it.id }
        require(byId.size == lenses.size) {
            "lens file: duplicate lens id ${lenses.groupBy { it.id }.filterValues { it.size > 1 }.keys.joinToString()}"
        }
    }

    fun byId(id: LensId): HabitLens? = byId[id]

    companion object {
        val DEFAULT: LensCatalogue by lazy { load("/coach/lenses.json") }

        fun load(resource: String, merchants: MerchantCatalogue = MerchantCatalogue.DEFAULT): LensCatalogue {
            val text = LensCatalogue::class.java.getResource(resource)?.readText()
                ?: throw IllegalStateException("lens file not found: $resource")
            return LensCatalogue(Json.decodeFromString<LensFile>(text), merchants)
        }

        private fun toLens(e: LensEntry, merchants: MerchantCatalogue): HabitLens {
            val id = LensId.parse(e.id) ?: throw IllegalArgumentException("lens file: bad lens id '${e.id}' (lowercase snake case)")
            e.brands.forEach { require(merchants.byBrand(it) != null) { "lens file: $id names brand '$it', which isn't in the merchant catalogue" } }
            val categories = e.categories.map { c ->
                TransactionCategory.entries.firstOrNull { it.name.equals(c, ignoreCase = true) && it != TransactionCategory.TRANSFER }
                    ?: throw IllegalArgumentException("lens file: $id has unknown or non-spending category '$c'")
            }
            e.subcategories.forEach { require(it.matches(Regex("[a-z][a-z0-9_]+"))) { "lens file: $id has a bad subcategory '$it'" } }
            e.keywords.forEach {
                require(MerchantCatalogue.matchForm(it).length >= 3) { "lens file: keyword '$it' of $id is too short to match safely" }
            }
            val rules = listOfNotNull(
                e.brands.takeIf { it.isNotEmpty() }?.let { LensRule.Brands(it.toSet()) },
                categories.takeIf { it.isNotEmpty() }?.let { LensRule.Categories(it.toSet()) },
                e.subcategories.takeIf { it.isNotEmpty() }?.let { LensRule.Subcategories(it.toSet()) },
                e.keywords.takeIf { it.isNotEmpty() }?.let { LensRule.Keywords(it.toSet()) }
            )
            require(rules.isNotEmpty()) { "lens file: $id has no brands, categories, subcategories or keywords" }
            return HabitLens(id, e.label, e.description, rules)
        }

        private fun parseWindow(w: LateNightEntry): LateNightWindow = try {
            LateNightWindow(LocalTime.parse(w.from), LocalTime.parse(w.to))
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("lens file: late-night times must look like 22:00, were '${w.from}' and '${w.to}'", e)
        }
    }
}
