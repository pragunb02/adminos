package dev.adminos.api.domain.coach.lenses

import dev.adminos.api.domain.financial.MerchantCatalogue
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import java.time.LocalTime

/** A lens's stable identity, e.g. `quick_commerce`: lowercase snake case, used in URLs, rows and JSON. */
@JvmInline
value class LensId(val value: String) {
    init {
        require(value.matches(PATTERN)) { "A lens id is lowercase snake case (2–40 chars), was '$value'" }
    }

    override fun toString() = value

    companion object {
        private val PATTERN = Regex("[a-z][a-z0-9_]{1,39}")

        /** The id, or null when [raw] isn't a well-formed lens id (for parsing user input). */
        fun parse(raw: String): LensId? = raw.takeIf { it.matches(PATTERN) }?.let(::LensId)
    }
}

/**
 * One way a transaction can belong to a lens (AOS-901). Each kind is a small, separately tested
 * specification; a new kind is one more class, and the lens file gains one field.
 */
sealed interface LensRule {
    fun matches(t: Transaction): Boolean

    /** The normalised merchant is one of these catalogue brands (case-insensitive, D-44). */
    data class Brands(val brands: Set<String>) : LensRule {
        private val keys = brands.map { it.trim().lowercase() }.toSet()
        override fun matches(t: Transaction) = t.merchantName?.trim()?.lowercase() in keys
    }

    data class Categories(val categories: Set<TransactionCategory>) : LensRule {
        override fun matches(t: Transaction) = t.category in categories
    }

    /** The categoriser's subcategory (e.g. `food_delivery`), whatever the brand. */
    data class Subcategories(val subcategories: Set<String>) : LensRule {
        private val keys = subcategories.map { it.lowercase() }.toSet()
        override fun matches(t: Transaction) = t.subcategory?.lowercase() in keys
    }

    /** A whole word in the merchant name, in catalogue matching form: "bar" matches "Toit Bar", not "Barbeque". */
    data class Keywords(val keywords: Set<String>) : LensRule {
        init {
            require(keywords.isNotEmpty()) { "A keyword rule needs at least one keyword" }
        }
        private val pattern = Regex("(?<![A-Z0-9])(" + keywords.joinToString("|") { Regex.escape(MerchantCatalogue.matchForm(it)) } + ")(?![A-Z0-9])")
        override fun matches(t: Transaction): Boolean {
            val name = t.merchantName ?: return false
            return pattern.containsMatchIn(MerchantCatalogue.matchForm(name))
        }
    }
}

/** A habit the owner watches (AOS-901, D-80): a label and the rules that pull spending into it. */
data class HabitLens(val id: LensId, val label: String, val description: String, val rules: List<LensRule>) {
    init {
        require(label.isNotBlank()) { "Lens $id needs a label" }
        require(rules.isNotEmpty()) { "Lens $id has no rules" }
    }

    fun ruleMatches(t: Transaction): Boolean = rules.any { it.matches(t) }
}

/**
 * "Late night" for the leaks page (AOS-902): local times from [from] up to, not including, [to].
 * It may cross midnight (22:00–05:00) or not (00:00–05:00).
 */
data class LateNightWindow(val from: LocalTime, val to: LocalTime) {
    init {
        require(from != to) { "The late-night window can't start and end at the same time ($from)" }
    }

    operator fun contains(time: LocalTime): Boolean =
        if (from < to) time >= from && time < to else time >= from || time < to

    override fun toString() = "$from–$to"
}
