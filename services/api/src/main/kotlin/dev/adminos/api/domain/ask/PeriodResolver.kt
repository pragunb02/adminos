package dev.adminos.api.domain.ask

import dev.adminos.api.domain.analytics.AnalyticsService
import dev.adminos.api.domain.analytics.DateRange
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** What [PeriodResolver] made of a [PeriodSpec]. */
sealed interface ResolvedPeriod {
    data class Ok(val range: DateRange) : ResolvedPeriod

    /** The whole period is after today: no spending can be in it yet. */
    data object Future : ResolvedPeriod

    /** Longer than the analytics allow ([AnalyticsService.MAX_DAYS]). */
    data object TooLong : ResolvedPeriod
}

/**
 * Turns a period into dates in the user's zone (AOS-712/710). [today] is the user's local date, computed by the
 * server from its clock and the zone in the user's token; the model never supplies it.
 * - Weeks start on Monday (D-46); "this week", "this month" and "this year" run to today.
 * - Periods are about money already spent, so a period that runs past today ends today, and one that starts
 *   after today is [ResolvedPeriod.Future].
 */
object PeriodResolver {
    fun resolve(spec: PeriodSpec, today: LocalDate): ResolvedPeriod {
        val (from, to) = when (spec.preset) {
            PeriodPreset.TODAY -> today to today
            PeriodPreset.YESTERDAY -> today.minusDays(1) to today.minusDays(1)
            PeriodPreset.THIS_WEEK -> monday(today) to today
            PeriodPreset.LAST_WEEK -> monday(today).minusWeeks(1) to monday(today).minusDays(1)
            PeriodPreset.THIS_MONTH -> today.withDayOfMonth(1) to today
            PeriodPreset.LAST_MONTH -> YearMonth.from(today).minusMonths(1).let { it.atDay(1) to it.atEndOfMonth() }
            PeriodPreset.LAST_7_DAYS -> today.minusDays(6) to today
            PeriodPreset.LAST_30_DAYS -> today.minusDays(29) to today
            PeriodPreset.THIS_YEAR -> today.withDayOfYear(1) to today
            PeriodPreset.MONTH -> (spec.month ?: error("validated by PeriodSpec")).let { it.atDay(1) to it.atEndOfMonth() }
            PeriodPreset.RANGE -> (spec.from ?: error("validated by PeriodSpec")) to (spec.to ?: error("validated by PeriodSpec"))
        }
        if (from.isAfter(today)) return ResolvedPeriod.Future
        val range = DateRange(from, if (to.isAfter(today)) today else to)
        if (range.days > AnalyticsService.MAX_DAYS) return ResolvedPeriod.TooLong
        return ResolvedPeriod.Ok(range)
    }

    private fun monday(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}

/**
 * Finds the periods a question names, in English and Hinglish ("aaj", "kal", "pichle mahine", "is hafte"),
 * in the order they appear. Deterministic, so the server can tell the model what "kal" means today and the
 * eval can score it. "kal" is read as yesterday: questions here are about money already spent.
 * A month name without a year is the latest such month that has started ("August" in October is this year's).
 */
object PeriodPhrases {
    private data class Phrase(val pattern: Regex, val spec: (LocalDate, MatchResult) -> PeriodSpec)

    private fun words(vararg alternatives: String) =
        Regex("(?<![\\p{L}\\p{Nd}])(?:${alternatives.joinToString("|")})(?![\\p{L}\\p{Nd}])")

    private fun fixed(preset: PeriodPreset): (LocalDate, MatchResult) -> PeriodSpec = { _, _ -> PeriodSpec.of(preset) }

    private val MONTHS: Map<String, Month> = buildMap {
        for (m in Month.entries) put(m.name.lowercase(Locale.ROOT), m)
        put("jan", Month.JANUARY); put("feb", Month.FEBRUARY); put("mar", Month.MARCH); put("apr", Month.APRIL)
        put("jun", Month.JUNE); put("jul", Month.JULY); put("aug", Month.AUGUST); put("sep", Month.SEPTEMBER)
        put("sept", Month.SEPTEMBER); put("oct", Month.OCTOBER); put("nov", Month.NOVEMBER); put("dec", Month.DECEMBER)
    }

    /** "may" is also a verb, so it counts as a month only with a year or after "in"/"of". */
    private val MONTH = Regex(
        "(?<![\\p{L}\\p{Nd}])(?:(?<name>${MONTHS.keys.filter { it != "may" }.sortedByDescending { it.length }.joinToString("|")})|(?<=\\b(?:in|of) )may|may(?= 20\\d\\d))" +
            "(?:\\s+(?<year>20\\d\\d))?(?![\\p{L}\\p{Nd}])"
    )

    private val PHRASES: List<Phrase> = listOf(
        Phrase(words("today", "aaj", "aj"), fixed(PeriodPreset.TODAY)),
        Phrase(words("yesterday", "kal"), fixed(PeriodPreset.YESTERDAY)),
        Phrase(words("this week", "is hafte", "iss hafte", "is week", "iss week", "is hafta", "iss hafta"), fixed(PeriodPreset.THIS_WEEK)),
        Phrase(words("last week", "previous week", "pichle hafte", "pichhle hafte", "pichle week", "pichhle week"), fixed(PeriodPreset.LAST_WEEK)),
        Phrase(words("this month", "is mahine", "iss mahine", "is month", "iss month", "is mahina", "iss mahina"), fixed(PeriodPreset.THIS_MONTH)),
        Phrase(words("last month", "previous month", "pichle mahine", "pichhle mahine", "pichle month", "pichhle month"), fixed(PeriodPreset.LAST_MONTH)),
        Phrase(words("last 7 days", "past 7 days", "last seven days", "past seven days", "pichle 7 din", "pichhle 7 din"), fixed(PeriodPreset.LAST_7_DAYS)),
        Phrase(words("last 30 days", "past 30 days", "last thirty days", "pichle 30 din", "pichhle 30 din"), fixed(PeriodPreset.LAST_30_DAYS)),
        Phrase(words("this year", "is saal", "iss saal", "is year", "iss year"), fixed(PeriodPreset.THIS_YEAR)),
        Phrase(MONTH) { today, m ->
            val name = m.groups["name"]?.value ?: "may"
            val month = MONTHS.getValue(name)
            val year = m.groups["year"]?.value?.toInt()
            PeriodSpec.month(if (year != null) YearMonth.of(year, month) else latest(month, today))
        }
    )

    fun detect(question: String, today: LocalDate): List<PeriodSpec> {
        val text = Normalizer.normalize(question, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        return PHRASES.flatMap { p -> p.pattern.findAll(text).map { it.range.first to p.spec(today, it) } }
            .sortedBy { it.first }
            .map { it.second }
            .distinct()
    }

    private fun latest(month: Month, today: LocalDate): YearMonth =
        YearMonth.of(today.year, month).let { if (it.isAfter(YearMonth.from(today))) it.minusYears(1) else it }
}
