package dev.adminos.api.domain.coach.leaks

import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.coach.lenses.HabitLens
import dev.adminos.api.domain.coach.lenses.LateNightWindow
import java.math.BigDecimal
import java.time.DayOfWeek

/**
 * The leaks page's numbers from facts (AOS-902). Pure: the same facts always give the same report.
 * - spent = spend − refunds; nothing is clipped, so a lens with only a refund shows negative (D-46);
 * - orders = spend transactions; late night counts orders with a known local time inside the window;
 * - weekdays are always Monday..Sunday, zero-filled;
 * - lenses are ordered by spent (largest first), then as in the lens file; merchants by spent, then name.
 */
object LeakCalculator {

    data class Input(
        val range: DateRange,
        val lenses: List<HabitLens>,
        val window: LateNightWindow,
        val current: List<LeakFact>,
        val previous: List<LeakFact>
    )

    fun report(input: Input): LeakReport {
        val lensLeaks = input.lenses.mapIndexed { index, lens ->
            val now = input.current.filter { lens.id in it.lenses }
            val before = input.previous.filter { lens.id in it.lenses }
            index to LensLeak(lens, numbers(now, before, input.window), merchants(now, before, input.window))
        }.sortedWith(compareByDescending<Pair<Int, LensLeak>> { it.second.numbers.spent }.thenBy { it.first }).map { it.second }
        return LeakReport(
            range = input.range,
            previousRange = input.range.previous(),
            lateNight = input.window,
            total = numbers(input.current.filter { it.lenses.isNotEmpty() }, input.previous.filter { it.lenses.isNotEmpty() }, input.window),
            lenses = lensLeaks
        )
    }

    fun numbers(current: List<LeakFact>, previous: List<LeakFact>, window: LateNightWindow): LeakNumbers {
        val orders = current.filter { it.isOrder }
        val timed = orders.filter { it.timeKnown }
        val late = timed.filter { it.local.toLocalTime() in window }
        val byDay = current.groupBy { it.weekday }
        return LeakNumbers(
            spent = spent(current),
            orders = orders.size,
            ordered = orders.sumOf { it.amount },
            lateNight = LateNight(late.size, timed.size, late.sumOf { it.amount }),
            weekdays = DayOfWeek.entries.map { d -> byDay[d].orEmpty().let { WeekdaySpend(d, it.count { f -> f.isOrder }, spent(it)) } },
            previousSpent = spent(previous)
        )
    }

    private fun merchants(current: List<LeakFact>, previous: List<LeakFact>, window: LateNightWindow): List<MerchantLeak> {
        val before = previous.groupBy { it.merchantKey }
        return current.groupBy { it.merchantKey }
            .map { (key, facts) -> MerchantLeak(displayName(facts), numbers(facts, before[key].orEmpty(), window)) }
            .sortedWith(compareByDescending<MerchantLeak> { it.numbers.spent }.thenBy { it.merchant.lowercase() })
    }

    /** The most used spelling of a merchant in the period (names differ only in case or spaces here). */
    private fun displayName(facts: List<LeakFact>): String =
        facts.groupingBy { it.merchant }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).first().key

    private fun spent(facts: List<LeakFact>): BigDecimal = facts.sumOf { it.signed }
}
