package dev.adminos.api.domain.coach.lenses

import dev.adminos.api.domain.financial.EverydaySpending
import dev.adminos.api.domain.financial.Transaction

/**
 * The habit lenses' view of everyday shopping (AOS-516): a charge in the quick-commerce or food
 * delivery lens is a grocery or food run, so the subscription detector never calls it a
 * subscription. The lenses' own rules decide (not the owner's per-merchant overrides, which are
 * about what they want to watch, not what a merchant is).
 */
class LensEverydaySpending(
    private val matcher: LensMatcher,
    private val lenses: Set<LensId> = DEFAULT_LENSES
) : EverydaySpending {
    init {
        require(lenses.isNotEmpty()) { "Everyday spending needs at least one lens" }
        lenses.forEach { require(matcher.catalogue.byId(it) != null) { "Unknown lens $it" } }
    }

    override fun isEveryday(t: Transaction): Boolean = matcher.lensesOf(t, LensOverrides.NONE).any { it in lenses }

    companion object {
        val DEFAULT_LENSES = setOf(LensId("quick_commerce"), LensId("food_delivery"))
    }
}
