package dev.adminos.api.domain.coach.lenses

import java.time.Clock
import java.util.UUID

/** A lens as the owner sees it: its rules plus their own additions and removals. */
data class LensView(val lens: HabitLens, val added: List<LensOverride>, val removed: List<LensOverride>)

/** What changing an override did. */
sealed interface OverrideOutcome {
    data class Saved(val override: LensOverride) : OverrideOutcome
    data object Cleared : OverrideOutcome
    data object NothingToClear : OverrideOutcome
    data class UnknownLens(val id: String) : OverrideOutcome
}

/**
 * The owner's habit lenses (AOS-901): the defaults from [LensMatcher.catalogue] and per-merchant
 * overrides. Other coach services read [overridesOf] and match with [matcher].
 */
class LensService(
    val matcher: LensMatcher,
    private val overrides: LensOverrideRepository,
    private val clock: Clock = Clock.systemUTC()
) {
    suspend fun list(userId: UUID): List<LensView> {
        val mine = overrides.findByUser(userId).groupBy { it.lensId }
        return matcher.lenses.map { lens ->
            val (added, removed) = mine[lens.id].orEmpty().partition { it.include }
            LensView(lens, added, removed)
        }
    }

    suspend fun overridesOf(userId: UUID): LensOverrides = LensOverrides(overrides.findByUser(userId))

    /** Adds ([include]) or removes a merchant from a lens for this user. */
    suspend fun setOverride(userId: UUID, lensId: String, merchant: String, include: Boolean): OverrideOutcome {
        val lens = known(lensId) ?: return OverrideOutcome.UnknownLens(lensId)
        return OverrideOutcome.Saved(overrides.upsert(LensOverride.of(userId, lens, merchant, include, clock.instant())))
    }

    /** Back to the lens's own rules for this merchant. */
    suspend fun clearOverride(userId: UUID, lensId: String, merchant: String): OverrideOutcome {
        val lens = known(lensId) ?: return OverrideOutcome.UnknownLens(lensId)
        return if (overrides.delete(userId, lens, LensOverride.keyOf(merchant))) OverrideOutcome.Cleared else OverrideOutcome.NothingToClear
    }

    fun lens(id: String): HabitLens? = known(id)?.let { matcher.catalogue.byId(it) }

    private fun known(id: String): LensId? = LensId.parse(id)?.takeIf { matcher.catalogue.byId(it) != null }
}
