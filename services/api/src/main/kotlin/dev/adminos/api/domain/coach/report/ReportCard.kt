package dev.adminos.api.domain.coach.report

import dev.adminos.api.domain.agent.briefing.BriefingSection
import dev.adminos.api.domain.agent.briefing.BriefingSectionOutput
import dev.adminos.api.domain.analytics.AnalyticsService
import dev.adminos.api.domain.analytics.Bucket
import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.coach.leaks.LeakFact
import dev.adminos.api.domain.coach.leaks.LeakService
import dev.adminos.api.domain.coach.lenses.HabitLens
import dev.adminos.api.domain.coach.limits.LimitMath
import dev.adminos.api.domain.coach.limits.LimitPeriod
import dev.adminos.api.domain.coach.limits.LimitService
import dev.adminos.api.domain.coach.limits.LimitStatus
import dev.adminos.api.domain.coach.limits.LimitSuggester
import dev.adminos.api.domain.common.formatInr
import dev.adminos.api.domain.common.toMoney
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import java.util.UUID

/** One lens over the report week, against its usual week. */
data class LensWeek(val lens: HabitLens, val spent: BigDecimal, val orders: Int, val ordered: BigDecimal, val lateOrders: Int, val lateSpent: BigDecimal, val usual: BigDecimal?) {
    /** spent − usual; null without a usual week. */
    val vsUsual: BigDecimal? get() = usual?.let { spent - it }
    val averageOrder: BigDecimal? get() = if (orders == 0) null else ordered.divide(BigDecimal(orders), 0, RoundingMode.HALF_UP)
}

/** How a limit ended (weekly) or stands (monthly, to date) on the report's last day. */
data class LimitOutcome(val status: LimitStatus) {
    val kept: Boolean get() = status.spent <= status.limit.amount
}

/** Everything the report card says (AOS-906); every number is computed here from the same facts as the leaks page. */
data class ReportCardFacts(
    val week: DateRange,
    val spent: BigDecimal,
    /** Median of the 8 weeks before, each the same 7 weekdays; null with fewer than [ReportCard.MIN_USUAL_WEEKS] weeks of data. */
    val usual: BigDecimal?,
    val lenses: List<LensWeek>,
    val limits: List<LimitOutcome>,
    val suggestedLimitReady: Boolean,
    /** Where "late night" starts (the lens file), for the words. */
    val lateFrom: java.time.LocalTime = java.time.LocalTime.of(22, 0)
) {
    val leakSpent: BigDecimal get() = lenses.fold(BigDecimal.ZERO) { a, l -> a + l.spent }
    /** The lens most below its usual week (only if below). */
    val best: LensWeek? get() = lenses.filter { (it.vsUsual?.signum() ?: 0) < 0 }.minByOrNull { it.vsUsual ?: BigDecimal.ZERO }
    /** The lens most above its usual week (only if above). */
    val worst: LensWeek? get() = lenses.filter { (it.vsUsual?.signum() ?: 0) > 0 }.maxByOrNull { it.vsUsual ?: BigDecimal.ZERO }
}

/** A suggestion rule (AOS-906): data in, one sentence backed by numbers out, or null when it doesn't apply. */
fun interface SuggestionRule {
    fun suggest(f: ReportCardFacts): String?
}

/**
 * The report card's rules, most concrete first; the first that applies is the one suggestion (exactly one, D-57).
 * A new rule is one entry here.
 */
object Suggestions {
    /** "Quick commerce: 9 orders, avg ₹410, 5 after 22:00. A ₹2,000 weekly limit would have saved ₹1,690." */
    val wouldHaveSaved = SuggestionRule { f ->
        val l = f.lenses.filter { it.orders >= MIN_ORDERS }.maxByOrNull { it.spent } ?: return@SuggestionRule null
        val limit = f.limits.map { it.status.limit }
            .firstOrNull { it.period == LimitPeriod.WEEKLY && it.scope == dev.adminos.api.domain.coach.limits.LimitScope.Lens(l.lens.id) }?.amount
        val cap = limit ?: l.usual?.let { capFrom(it) } ?: return@SuggestionRule null
        val saved = l.spent - cap
        if (saved.signum() <= 0) return@SuggestionRule null
        "${l.lens.label}: ${l.orders} orders, avg ${l.averageOrder?.formatInr()}, ${l.lateOrders} after ${f.lateFrom}. " +
            "A ${cap.formatInr()} weekly limit would have saved ${saved.formatInr()}."
    }

    /** "6 orders after 22:00 came to ₹2,050. Skipping them would have saved ₹2,050." */
    val lateNight = SuggestionRule { f ->
        val orders = f.lenses.sumOf { it.lateOrders }
        if (orders < MIN_ORDERS) return@SuggestionRule null
        val spent = f.lenses.fold(BigDecimal.ZERO) { a, l -> a + l.lateSpent }
        "$orders orders after ${f.lateFrom} came to ${spent.formatInr()}. Skipping them would have saved ${spent.formatInr()}."
    }

    /** Below the usual: say by how much, and keep it. */
    val belowUsual = SuggestionRule { f ->
        val usual = f.lenses.mapNotNull { it.usual }.takeIf { it.size == f.lenses.size && it.isNotEmpty() }?.fold(BigDecimal.ZERO, BigDecimal::add) ?: return@SuggestionRule null
        val under = usual - f.leakSpent
        if (usual.signum() <= 0 || under.multiply(BigDecimal(10)) < usual) return@SuggestionRule null
        "Habits cost ${f.leakSpent.formatInr()} this week, ${under.formatInr()} under your usual ${usual.formatInr()}. Same again next week puts ${under.multiply(BigDecimal(4)).formatInr()} a month in your pocket."
    }

    /** Always applies: the week's habit total, so there is always exactly one line. */
    val steady = SuggestionRule { f ->
        if (f.leakSpent.signum() <= 0) "No habit spending this week. Keep the streak going."
        else "Habits cost ${f.leakSpent.formatInr()} this week. Pick one lens on the leaks page and set a limit for it."
    }

    val ORDER: List<SuggestionRule> = listOf(wouldHaveSaved, lateNight, belowUsual, steady)

    const val MIN_ORDERS = 3

    /** Like the limit suggestion (D-83): usual − 10%, down to ₹50, at least ₹100. */
    fun capFrom(usual: BigDecimal): BigDecimal? {
        val v = usual.multiply(BigDecimal(100 - LimitSuggester.STEP_PERCENT)).divide(BigDecimal(100), 2, RoundingMode.DOWN)
        val rounded = v.divide(LimitSuggester.ROUND_TO, 0, RoundingMode.DOWN).multiply(LimitSuggester.ROUND_TO).setScale(2)
        return rounded.takeIf { it >= LimitSuggester.MIN_AMOUNT }
    }

    fun pick(f: ReportCardFacts, rules: List<SuggestionRule> = ORDER): String = rules.firstNotNullOf { it.suggest(f) }
}

/** The report card's words. */
object ReportCardWriter {
    fun lines(f: ReportCardFacts, suggestion: String): List<String> = buildList {
        add(spendLine(f))
        val bw = listOfNotNull(
            f.best?.let { "Best: ${it.lens.label}, ${it.vsUsual?.negate()?.formatInr()} below usual" },
            f.worst?.let { "Worst: ${it.lens.label}, ${it.vsUsual?.formatInr()} above usual" }
        )
        if (bw.isNotEmpty()) add(bw.joinToString(". ") + ".")
        if (f.limits.isNotEmpty()) add("Limits: " + f.limits.joinToString("; ") { limit(it) } + ".")
        if (f.suggestedLimitReady) add("A suggested limit is ready on the leaks page.")
        add("Try this: $suggestion")
    }

    private fun spendLine(f: ReportCardFacts): String {
        val usual = f.usual ?: return "Report card: ${f.spent.formatInr()} spent; not enough weeks yet for your usual week."
        val diff = f.spent - usual
        val vs = when {
            diff.signum() > 0 -> "${diff.formatInr()} more than your usual ${usual.formatInr()}"
            diff.signum() < 0 -> "${diff.negate().formatInr()} less than your usual ${usual.formatInr()}"
            else -> "the same as your usual week"
        }
        return "Report card: ${f.spent.formatInr()} spent, $vs."
    }

    private fun limit(o: LimitOutcome): String {
        val l = o.status.limit
        val period = if (l.period == LimitPeriod.WEEKLY) "weekly" else "monthly so far"
        return if (o.kept) "${l.label} kept (${o.status.spent.formatInr()} of ${l.amount.formatInr()} $period)"
        else "${l.label} over by ${(o.status.spent - l.amount).formatInr()} (${l.amount.formatInr()} $period)"
    }
}

/**
 * The Sunday report card (AOS-906) as a section of the weekly briefing: the week the briefing covers
 * (by default Monday–Sunday, sent Monday 08:00), against the median of the 8 weeks before.
 */
class ReportCard(private val analytics: AnalyticsService, private val leaks: LeakService, private val limits: LimitService) : BriefingSection {

    override suspend fun render(userId: UUID, period: DateRange, zone: ZoneId): BriefingSectionOutput {
        val f = facts(userId, period, zone)
        val suggestion = Suggestions.pick(f)
        return BriefingSectionOutput("reportCard", ReportCardWriter.lines(f, suggestion), structured(f, suggestion))
    }

    suspend fun facts(userId: UUID, week: DateRange, zone: ZoneId): ReportCardFacts {
        require(week.days == 7L) { "a report card covers 7 days" }
        val before = DateRange(week.from.minusDays(7L * USUAL_WEEKS), week.from.minusDays(1))
        val daily = analytics.series(userId, DateRange(before.from, week.to), Bucket.DAY, zone)
        val spent = daily.filter { !it.start.isBefore(week.from) }.fold(BigDecimal.ZERO) { a, p -> a + p.money.spent }
        val windows = windows(week)
        val usualTotals = windows.map { w -> daily.filter { !it.start.isBefore(w.from) && !it.start.isAfter(w.to) }.fold(BigDecimal.ZERO) { a, p -> a + p.money.spent } }
        val facts = leaks.facts(userId, DateRange(before.from, week.to), zone)
        val thisWeek = facts.filter { inRange(it, week) }
        val lensWeeks = leaks.matcher.lenses.map { lens -> lensWeek(lens, thisWeek, windows.map { w -> facts.filter { lens.id in it.lenses && inRange(it, w) } }) }
        return ReportCardFacts(week, spent, usualOf(usualTotals), lensWeeks, limitOutcomes(userId, week, zone), suggestionReady(userId, zone),
            leaks.matcher.catalogue.lateNight.from)
    }

    private fun lensWeek(lens: HabitLens, thisWeek: List<LeakFact>, previous: List<List<LeakFact>>): LensWeek {
        val mine = thisWeek.filter { lens.id in it.lenses }
        val orders = mine.filter { it.isOrder }
        val late = orders.filter { it.timeKnown && it.local.toLocalTime() in leaks.matcher.catalogue.lateNight }
        return LensWeek(lens, mine.sumOf { it.signed }, orders.size, orders.sumOf { it.amount }, late.size, late.sumOf { it.amount },
            usualOf(previous.map { w -> w.sumOf { it.signed } }))
    }

    private suspend fun limitOutcomes(userId: UUID, week: DateRange, zone: ZoneId): List<LimitOutcome> {
        val mine = limits.limitsOf(userId).sortedWith(compareBy({ it.createdAt }, { it.label }))
        if (mine.isEmpty()) return emptyList()
        val from = mine.minOf { it.period.rangeOf(week.to).from }
        val facts = leaks.facts(userId, DateRange(from, week.to), zone)
        return mine.map { l -> LimitOutcome(LimitMath.status(l, week.to, facts.filter { inRange(it, l.period.rangeOf(week.to)) })) }
    }

    private suspend fun suggestionReady(userId: UUID, zone: ZoneId): Boolean = limits.overview(userId, zone).suggestions.isNotEmpty()

    private fun structured(f: ReportCardFacts, suggestion: String): Map<String, Any?> = mapOf(
        "spent" to f.spent.toMoney(), "usual" to f.usual?.toMoney(), "leakSpent" to f.leakSpent.toMoney(),
        "best" to f.best?.lens?.id?.value, "worst" to f.worst?.lens?.id?.value,
        "lenses" to f.lenses.map { mapOf("lens" to it.lens.id.value, "spent" to it.spent.toMoney(), "orders" to it.orders, "lateOrders" to it.lateOrders, "usual" to it.usual?.toMoney()) },
        "limits" to f.limits.map { mapOf("limit" to it.status.limit.id.toString(), "spent" to it.status.spent.toMoney(), "amount" to it.status.limit.amount.toMoney(), "kept" to it.kept) },
        "suggestedLimitReady" to f.suggestedLimitReady, "suggestion" to suggestion
    )

    companion object {
        const val USUAL_WEEKS = 8
        /** A usual week needs at least this many of the 8 weeks to have any spending. */
        const val MIN_USUAL_WEEKS = 2

        /** The 8 seven-day windows before [week], oldest first, aligned to its weekdays. */
        fun windows(week: DateRange): List<DateRange> =
            (USUAL_WEEKS downTo 1).map { n -> week.from.minusDays(7L * n).let { DateRange(it, it.plusDays(6)) } }

        /** The median of the weeks, once at least [MIN_USUAL_WEEKS] had spending (the weeks without count as ₹0). */
        fun usualOf(weeks: List<BigDecimal>): BigDecimal? =
            if (weeks.count { it.signum() != 0 } < MIN_USUAL_WEEKS) null else LimitSuggester.median(weeks)

        private fun inRange(f: LeakFact, r: DateRange) = f.local.toLocalDate().let { !it.isBefore(r.from) && !it.isAfter(r.to) }
    }
}
