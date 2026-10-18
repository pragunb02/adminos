package dev.adminos.api.domain.ingestion.cash

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** What a date phrase in the text means, against the user's own today. */
sealed interface DateFinding {
    data class On(val date: LocalDate, val assumed: Boolean = false) : DateFinding
    /** "tomorrow" or a date after today. */
    data object Future : DateFinding
    /** "31/02": no such day. */
    data object Invalid : DateFinding
}

/** A segment's text with its date phrase taken out, and what that phrase meant (null: none). */
data class Dated(val rest: String, val finding: DateFinding?)

/**
 * Relative and written dates in cash phrases (AOS-718), English and Hinglish, on the user's calendar:
 * - today / aaj; yesterday / last night; "kal" and "parso" (both directions in Hindi: the past is assumed, flagged);
 *   day before yesterday; "N days ago" / "N din pehle"; tomorrow (refused: cash is logged once spent);
 * - weekdays: "monday" is the latest Monday on or before today, "last monday" the one before today;
 * - written dates, day first (D-55): "12 oct", "12th october", "oct 12", "12/10", "12-10-2026". Without a year,
 *   the latest such day on or before today (so "29 feb" finds the last leap day).
 * Pure; text is already lower case.
 */
object PhraseDates {
    private const val MONTHS = "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"
    private const val WEEKDAYS = "mon(?:day)?|tue(?:s(?:day)?)?|wed(?:nesday)?|thu(?:r(?:s(?:day)?)?)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?"

    private val DAY_MONTH = Regex("\\b(?:on\\s+)?(\\d{1,2})(?:st|nd|rd|th)?\\s*(?:of\\s+)?($MONTHS)\\b(?:\\s+(\\d{4}))?")
    private val MONTH_DAY = Regex("\\b(?:on\\s+)?($MONTHS)\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b(?:,?\\s+(\\d{4}))?")
    private val NUMERIC = Regex("(?<![\\d.])(?:on\\s+)?(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2}|\\d{4}))?(?![\\d.])")
    private val DAYS_AGO = Regex("\\b(\\d{1,3})\\s+(?:days?\\s+ago|din\\s+pehle|din\\s+pahle)\\b")
    private val WEEKDAY = Regex("\\b(?:(last|pichle|on)\\s+)?($WEEKDAYS)\\b")
    private val WORDS: List<Pair<Regex, (LocalDate) -> DateFinding>> = listOf(
        Regex("\\bday before yesterday\\b") to { t -> DateFinding.On(t.minusDays(2)) },
        Regex("\\b(?:parso|parson|parsoon)\\b") to { t -> DateFinding.On(t.minusDays(2), assumed = true) },
        Regex("\\b(?:yesterday|yday|last night|kal raat)\\b") to { t -> DateFinding.On(t.minusDays(1)) },
        Regex("\\bkal\\b") to { t -> DateFinding.On(t.minusDays(1), assumed = true) },
        Regex("\\b(?:tomorrow|tmrw)\\b") to { _ -> DateFinding.Future },
        Regex("\\b(?:today|aaj|this morning|tonight|abhi)\\b") to { t -> DateFinding.On(t) }
    )

    /** Takes the first date phrase out of [segment]; the rest of the text is returned without it. */
    fun extract(segment: String, today: LocalDate): Dated {
        val candidates = buildList {
            DAY_MONTH.find(segment)?.let { m -> add(m to written(m.groupValues[1], m.groupValues[2], m.groupValues[3], today)) }
            MONTH_DAY.find(segment)?.let { m -> add(m to written(m.groupValues[2], m.groupValues[1], m.groupValues[3], today)) }
            NUMERIC.find(segment)?.let { m -> add(m to numeric(m.groupValues[1], m.groupValues[2], m.groupValues[3], today)) }
            DAYS_AGO.find(segment)?.let { m -> add(m to DateFinding.On(today.minusDays(m.groupValues[1].toLong()))) }
            WORDS.forEach { (re, meaning) -> re.find(segment)?.let { m -> add(m to meaning(today)) } }
            WEEKDAY.find(segment)?.let { m -> add(m to weekday(m.groupValues[1], m.groupValues[2], today)) }
        }
        val (match, finding) = candidates.minWithOrNull(compareBy<Pair<MatchResult, DateFinding>> { it.first.range.first }.thenByDescending { it.first.value.length })
            ?: return Dated(segment, null)
        val rest = (segment.substring(0, match.range.first) + " " + segment.substring(match.range.last + 1)).replace(Regex("\\s+"), " ").trim()
        return Dated(rest, finding)
    }

    private fun written(day: String, month: String, year: String, today: LocalDate): DateFinding {
        val m = monthOf(month)
        return resolve(day.toInt(), m, year.toIntOrNull(), today)
    }

    private fun numeric(day: String, month: String, year: String, today: LocalDate): DateFinding {
        val y = year.toIntOrNull()?.let { if (it < 100) 2000 + it else it }
        return resolve(day.toInt(), month.toInt(), y, today)
    }

    /** A day and month (and maybe a year): that date, or without a year the latest one on or before [today]. */
    private fun resolve(day: Int, month: Int, year: Int?, today: LocalDate): DateFinding {
        if (month !in 1..12 || day !in 1..31) return DateFinding.Invalid
        val monthDay = try { MonthDay.of(month, day) } catch (_: DateTimeException) { return DateFinding.Invalid }
        if (year != null) {
            val date = try { LocalDate.of(year, month, day) } catch (_: DateTimeException) { return DateFinding.Invalid }
            return if (date.isAfter(today)) DateFinding.Future else DateFinding.On(date)
        }
        // A day later this month or next is a slip, not last year: "20 oct" on 17 Oct is refused as the future.
        if (monthDay.isValidYear(today.year)) {
            val ahead = ChronoUnit.DAYS.between(today, monthDay.atYear(today.year))
            if (ahead in 1..NEAR_FUTURE_DAYS) return DateFinding.Future
        }
        // The latest year (this one or earlier) where this day exists and isn't after today; 29 Feb looks back up to 8 years.
        val date = (0L..8L).map { today.year - it }.filter { monthDay.isValidYear(it.toInt()) }.map { monthDay.atYear(it.toInt()) }
            .firstOrNull { !it.isAfter(today) } ?: return DateFinding.Invalid
        return DateFinding.On(date)
    }

    private fun weekday(qualifier: String, name: String, today: LocalDate): DateFinding {
        val dow = DayOfWeek.entries.first { it.name.lowercase().startsWith(name.take(3)) }
        val onOrBefore = today.with(TemporalAdjusters.previousOrSame(dow))
        val last = qualifier == "last" || qualifier == "pichle"
        return DateFinding.On(if (last && onOrBefore == today) today.minusWeeks(1) else onOrBefore)
    }

    private fun monthOf(s: String): Int =
        listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(s.take(3)) + 1

    private const val NEAR_FUTURE_DAYS = 31L

    /** Whether [date] is more than a year before [today]. */
    fun isOld(date: LocalDate, today: LocalDate): Boolean = ChronoUnit.DAYS.between(date, today) > 366
}
