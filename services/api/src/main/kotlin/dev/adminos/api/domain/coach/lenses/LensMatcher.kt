package dev.adminos.api.domain.coach.lenses

import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionNature

/**
 * Decides which lenses a transaction belongs to (AOS-901). For one transaction and one lens, in order:
 * 1. only spending and refunds count (D-39): transfers, reversals and income never match;
 * 2. the owner removed this merchant from the lens → no;
 * 3. the owner added it → yes;
 * 4. a person-to-person UPI payment (a settle-up, not a habit) → no;
 * 5. any of the lens's rules matches → yes.
 *
 * A refund is matched exactly like spending, so it nets off the lens it reverses (D-46).
 * A transaction may sit in more than one lens; totals across lenses use the union.
 */
class LensMatcher(
    val catalogue: LensCatalogue = LensCatalogue.DEFAULT,
    private val people: PersonToPerson = PersonToPerson()
) {
    val lenses: List<HabitLens> get() = catalogue.lenses

    fun lensesOf(t: Transaction, overrides: LensOverrides): Set<LensId> {
        if (t.nature !in COUNTED) return emptySet()
        val person = people.isPersonToPerson(t)
        return lenses.filter { lens ->
            when (overrides.choiceFor(lens.id, t.merchantName)) {
                false -> false
                true -> true
                null -> !person && lens.ruleMatches(t)
            }
        }.map { it.id }.toSet()
    }

    fun belongsTo(t: Transaction, lens: LensId, overrides: LensOverrides): Boolean = lens in lensesOf(t, overrides)

    companion object {
        val COUNTED = setOf(TransactionNature.SPEND, TransactionNature.REFUND)
    }
}
