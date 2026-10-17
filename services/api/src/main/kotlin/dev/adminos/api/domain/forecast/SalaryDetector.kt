package dev.adminos.api.domain.forecast

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** A salary learnt from history: the same payer, about the same amount, about the same day, every month. */
data class DetectedSalary(
    /** The payer as the bank showed it; null when credits came without a name. */
    val payer: String?,
    /** The median of the last three credits. */
    val typicalAmount: BigDecimal,
    val lastReceivedOn: LocalDate,
    val lastAmount: BigDecimal,
    val expectedOn: LocalDate,
    /** How many monthly credits in a row it was learnt from. */
    val credits: Int
)

/** Why no salary could be used. */
enum class NoSalaryReason {
    /** No payer credits a similar amount every month. */
    NO_REGULAR_CREDIT,
    /** One payer credits regularly, but the amounts differ too much to forecast. */
    AMOUNT_VARIES,
    /** One payer credits every month, but on days too far apart to forecast. */
    DATE_VARIES,
    /** The regular credit was expected more than [SalaryDetector.LATE_DAYS] days ago and hasn't come. */
    STOPPED
}

sealed interface SalaryFinding {
    /** A usable salary; [late] when its expected day has passed without it (up to [SalaryDetector.LATE_DAYS] days). */
    data class Found(val salary: DetectedSalary, val late: Boolean) : SalaryFinding
    data class NotFound(val reason: NoSalaryReason, val payer: String? = null, val lastSeen: LocalDate? = null) : SalaryFinding
}

/**
 * Finds the salary in income credits (AOS-714), deterministically, so it can be explained:
 * 1. credits are grouped by payer (case and punctuation ignored); of credits within [CLUSTER_DAYS] days of each
 *    other only the largest counts;
 * 2. the best chain of credits [MIN_GAP_DAYS]..[MAX_GAP_DAYS] days apart must have at least [MIN_CREDITS], without
 *    similar credits in between (weekly payouts aren't a monthly salary);
 * 3. at least two of the last three must be within ±[AMOUNT_TOLERANCE] of their median (a bonus month is fine),
 *    which must be at least [MIN_SALARY];
 * 4. their days of the month must be within [MAX_DAY_SPREAD] days, read either as plain days (the 7th) or
 *    as days before the month's end (the last working day), whichever is tighter;
 * 5. the next salary is that day in the next month, moved off a weekend the way this payer did before (Friday
 *    before or Monday after; not moved when there's no sign). Among several payers, the largest salary wins.
 */
class SalaryDetector {

    fun detect(credits: List<IncomeCredit>, today: LocalDate): SalaryFinding {
        val findings = credits.filter { !it.day.isAfter(today) }.groupBy { payerKey(it.payer) }.values.map { assess(it, today) }
        val found = findings.filterIsInstance<SalaryFinding.Found>()
        if (found.isNotEmpty()) return found.maxWith(compareBy<SalaryFinding.Found> { it.salary.typicalAmount }.thenBy { it.salary.lastReceivedOn })
        // Report the most specific reason a regular-looking payer failed, else that there is none.
        val failed = findings.filterIsInstance<SalaryFinding.NotFound>()
        return failed.filter { it.reason != NoSalaryReason.NO_REGULAR_CREDIT }.maxByOrNull { it.lastSeen ?: LocalDate.MIN }
            ?: SalaryFinding.NotFound(NoSalaryReason.NO_REGULAR_CREDIT)
    }

    private fun assess(credits: List<IncomeCredit>, today: LocalDate): SalaryFinding {
        val payer = credits.maxBy { it.day }.payer
        val run = monthlyRun(mainCredits(credits))
        if (run.size < MIN_CREDITS) return SalaryFinding.NotFound(NoSalaryReason.NO_REGULAR_CREDIT)
        val last3 = run.takeLast(MIN_CREDITS)
        val typical = Stats.median(last3.map { it.amount }).setScale(2, RoundingMode.HALF_UP)
        if (typical < MIN_SALARY || paidMoreOftenThanMonthly(credits, run, typical)) return SalaryFinding.NotFound(NoSalaryReason.NO_REGULAR_CREDIT)
        val last = run.last()
        if (last3.count { (it.amount - typical).abs() <= typical.multiply(AMOUNT_TOLERANCE) } < MIN_CREDITS - 1) {
            return SalaryFinding.NotFound(NoSalaryReason.AMOUNT_VARIES, payer, last.day)
        }
        val pattern = DayPattern.of(last3.map { it.day }, run.map { it.day }) ?: return SalaryFinding.NotFound(NoSalaryReason.DATE_VARIES, payer, last.day)
        val expected = pattern.nextAfter(last.day)
        val lateBy = ChronoUnit.DAYS.between(expected, today)
        if (lateBy > LATE_DAYS) return SalaryFinding.NotFound(NoSalaryReason.STOPPED, payer, last.day)
        val salary = DetectedSalary(payer, typical, last.day, last.amount, expected, run.size)
        return SalaryFinding.Found(salary, late = lateBy > 0)
    }

    /**
     * Weekly or fortnightly payouts also contain a "monthly" chain. A real monthly salary has, on average, less than one
     * other credit of a similar size (half or more of [typical]) between two paydays.
     */
    private fun paidMoreOftenThanMonthly(credits: List<IncomeCredit>, run: List<IncomeCredit>, typical: BigDecimal): Boolean {
        val paydays = run.map { it.day }.toSet()
        val between = credits.count { c ->
            c.day.isAfter(run.first().day) && c.day.isBefore(run.last().day) && c.day !in paydays && c.amount.multiply(BigDecimal(2)) >= typical
        }
        return between >= run.size - 1
    }

    /** Of credits that arrive within [CLUSTER_DAYS] days of each other (a salary and a reimbursement), keeps the largest. */
    private fun mainCredits(credits: List<IncomeCredit>): List<IncomeCredit> {
        val clusters = mutableListOf<MutableList<IncomeCredit>>()
        for (c in credits.sortedBy { it.day }) {
            val current = clusters.lastOrNull()
            if (current != null && ChronoUnit.DAYS.between(current.first().day, c.day) <= CLUSTER_DAYS) current += c else clusters += mutableListOf(c)
        }
        return clusters.map { cluster -> cluster.maxWith(compareBy<IncomeCredit> { it.amount }.thenBy { it.day }) }
    }

    /**
     * The best chain of credits about a month apart: of those long enough, the one with the largest recent amounts (then
     * the newest); else the longest. A monthly reimbursement can make a chain too, but the salary's is larger.
     */
    private fun monthlyRun(credits: List<IncomeCredit>): List<IncomeCredit> {
        if (credits.isEmpty()) return emptyList()
        val length = IntArray(credits.size) { 1 }
        val total = Array(credits.size) { credits[it].amount }
        val previous = IntArray(credits.size) { -1 }
        for (i in credits.indices) for (j in 0 until i) {
            if (ChronoUnit.DAYS.between(credits[j].day, credits[i].day) !in MIN_GAP_DAYS..MAX_GAP_DAYS) continue
            val len = length[j] + 1
            val sum = total[j] + credits[i].amount
            if (len > length[i] || (len == length[i] && sum > total[i])) { length[i] = len; total[i] = sum; previous[i] = j }
        }
        fun chain(end: Int) = generateSequence(end) { previous[it].takeIf { p -> p >= 0 } }.map { credits[it] }.toList().reversed()
        val long = credits.indices.filter { length[it] >= MIN_CREDITS }.map(::chain)
        if (long.isNotEmpty()) return long.maxWith(compareBy<List<IncomeCredit>> { c -> Stats.median(c.takeLast(MIN_CREDITS).map { it.amount }) }.thenBy { it.last().day })
        return chain(credits.indices.maxWith(compareBy<Int> { length[it] }.thenBy { total[it] }))
    }

    /**
     * Where in the month a salary lands, and how a weekend moves it. [fromEnd] = false: the [offset]th day (clamped to
     * short months). [fromEnd] = true: [offset] ≤ 0 days from the month's last day, or the [offset]th day early in the
     * month, so "the 31st, the 30th or the 1st" read as one pattern.
     */
    internal data class DayPattern(val offset: Int, val fromEnd: Boolean, val shift: WeekendShift = WeekendShift.NONE) {
        /** The nominal day in [month], before any weekend move. */
        fun dateIn(month: YearMonth): LocalDate = when {
            !fromEnd -> month.atDay(minOf(offset, month.lengthOfMonth()))
            offset <= 0 -> month.atEndOfMonth().plusDays(offset.toLong())
            else -> month.atDay(offset)
        }

        /** The payday this pattern gives for the month nearest [credit]. */
        fun paydayNear(credit: LocalDate): LocalDate =
            (-1L..1L).map { shift.apply(dateIn(YearMonth.from(credit).plusMonths(it))) }.minBy { kotlin.math.abs(ChronoUnit.DAYS.between(it, credit)) }

        /** The first payday of this pattern at least [MIN_GAP_DAYS] − 5 days after [last]. */
        fun nextAfter(last: LocalDate): LocalDate =
            generateSequence(YearMonth.from(last)) { it.plusMonths(1) }.map { shift.apply(dateIn(it)) }
                .first { ChronoUnit.DAYS.between(last, it) >= MIN_GAP_DAYS - 5 }

        companion object {
            /**
             * The pattern for these paydays, or null when [recent] spread more than [MAX_DAY_SPREAD] days. Among the days
             * in their range and the three weekend habits, the one that explains most of [all] exactly wins; ties go
             * to no weekend move, then to the later day (the careful side for "money left").
             */
            fun of(recent: List<LocalDate>, all: List<LocalDate>): DayPattern? {
                val plain = recent.map { it.dayOfMonth }
                val fromEnd = recent.map { endOffset(it) }
                val plainSpread = plain.max() - plain.min()
                val endSpread = fromEnd.max() - fromEnd.min()
                if (minOf(plainSpread, endSpread) > MAX_DAY_SPREAD) return null
                // "29th, 30th, 31st" is the end of the month, not the 30th: on a tie, late days count from the end.
                val isFromEnd = endSpread < plainSpread || (endSpread == plainSpread && plain.all { it >= 25 })
                val values = if (isFromEnd) fromEnd else plain
                val candidates = (values.min()..values.max()).flatMap { o -> WeekendShift.entries.map { DayPattern(o, isFromEnd, it) } }
                return candidates.maxWith(compareBy<DayPattern> { p -> all.count { p.paydayNear(it) == it } }
                    .thenBy { it.shift == WeekendShift.NONE }.thenBy { it.offset }.thenBy { it.shift == WeekendShift.AFTER })
            }

            /** Days from the 16th on count back from the month's end (0 = last day); earlier days are themselves. */
            private fun endOffset(day: LocalDate): Int =
                if (day.dayOfMonth >= 16) day.dayOfMonth - day.lengthOfMonth() else day.dayOfMonth
        }
    }

    /** What a payer does when payday falls on a weekend: nothing seen, the Friday before, or the Monday after. */
    internal enum class WeekendShift {
        NONE, BEFORE, AFTER;

        fun apply(date: LocalDate): LocalDate = when {
            this == NONE || !isWeekend(date) -> date
            this == BEFORE -> generateSequence(date) { it.minusDays(1) }.first { !isWeekend(it) }
            else -> generateSequence(date) { it.plusDays(1) }.first { !isWeekend(it) }
        }

        private fun isWeekend(d: LocalDate) = d.dayOfWeek == java.time.DayOfWeek.SATURDAY || d.dayOfWeek == java.time.DayOfWeek.SUNDAY
    }

    companion object {
        const val MIN_CREDITS = 3
        const val MIN_GAP_DAYS = 25L
        const val MAX_GAP_DAYS = 36L
        const val CLUSTER_DAYS = 10L
        const val MAX_DAY_SPREAD = 6
        const val LATE_DAYS = 10L
        val AMOUNT_TOLERANCE = BigDecimal("0.20")
        /** Below this a monthly credit is interest, cashback or pocket money, not something to plan a month on. */
        val MIN_SALARY = BigDecimal("5000")

        fun payerKey(payer: String?): String = payer.orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    }
}
