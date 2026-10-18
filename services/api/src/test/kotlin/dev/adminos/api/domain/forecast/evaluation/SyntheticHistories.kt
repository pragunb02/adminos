package dev.adminos.api.domain.forecast.evaluation

import dev.adminos.api.domain.forecast.DueSource
import dev.adminos.api.domain.forecast.IncomeCredit
import dev.adminos.api.domain.forecast.KnownDue
import dev.adminos.api.domain.forecast.SpendDay
import dev.adminos.api.domain.forecast.SpendHistory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.util.SplittableRandom
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** When a salary lands: the 1st (a weekend moves it to the Friday before), the last working day, or the 7th (a weekend moves it to Monday). */
enum class PayDay { FIRST, LAST_WORKING_DAY, SEVENTH, IRREGULAR }

/**
 * A SYNTHETIC spender (AOS-714): no real data. Everyday spending is a daily coin flip with a log-normal
 * amount, by weekday and weekend, plus random big purchases, a monthly drift and a festive season
 * (October–November). Recurring payments and the salary follow fixed schedules.
 */
data class Persona(
    val name: String,
    val weekdayChance: Double,
    val weekendChance: Double,
    val weekdayMedian: Double,
    val weekendMedian: Double,
    val sigma: Double = 0.8,
    /** Expected big purchases per month and their range in rupees. */
    val spikesPerMonth: Double = 0.0,
    val spikeMin: Double = 5_000.0,
    val spikeMax: Double = 25_000.0,
    /** Multiplicative change of everyday spending per month (0.02 = +2%). */
    val driftPerMonth: Double = 0.0,
    val festiveBoost: Double = 1.0,
    val payDay: PayDay = PayDay.FIRST,
    val salary: Double = 85_000.0
)

/** A recurring payment on a fixed day of each month. */
data class Scheduled(val source: DueSource, val name: String, val dayOfMonth: Int, val amount: (YearMonth) -> BigDecimal)

/** A generated history with its schedule, so a backtest can know what was due. */
class SyntheticHistory(
    val persona: Persona,
    val seed: Long,
    val start: LocalDate,
    val end: LocalDate,
    private val everyday: Map<LocalDate, BigDecimal>,
    private val schedule: List<Scheduled>,
    val salaries: List<IncomeCredit>
) {
    /** The history as it was known at the end of [today]: nothing after it. */
    fun asOf(today: LocalDate): SpendHistory {
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.map { d ->
            SpendDay(d, everyday[d] ?: BigDecimal.ZERO, recurringOn(d))
        }.toList()
        return SpendHistory(days, start)
    }

    fun totalOn(day: LocalDate): BigDecimal = (everyday[day] ?: BigDecimal.ZERO) + recurringOn(day)

    fun monthTotal(month: YearMonth): BigDecimal =
        generateSequence(month.atDay(1)) { it.plusDays(1) }.takeWhile { !it.isAfter(month.atEndOfMonth()) }.fold(BigDecimal.ZERO) { acc, d -> acc + totalOn(d) }

    fun spentBetween(from: LocalDate, to: LocalDate): BigDecimal =
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.fold(BigDecimal.ZERO) { acc, d -> acc + totalOn(d) }

    /** Known dues after [today] up to [until] (a forecaster sees bills, renewals and mandates ahead of time). */
    fun duesAfter(today: LocalDate, until: LocalDate): List<KnownDue> =
        generateSequence(today.plusDays(1)) { it.plusDays(1) }.takeWhile { !it.isAfter(until) }.flatMap { d ->
            schedule.filter { dateIn(it, YearMonth.from(d)) == d }.map { KnownDue(it.source, it.name, d, it.amount(YearMonth.from(d))) }
        }.toList()

    fun incomesUpTo(today: LocalDate): List<IncomeCredit> = salaries.filter { !it.day.isAfter(today) }

    /** The recurring payment called [name] (AOS-721's late-bills measurement). */
    fun scheduled(name: String): Scheduled = schedule.single { it.name == name }

    private fun recurringOn(day: LocalDate): BigDecimal =
        schedule.filter { dateIn(it, YearMonth.from(day)) == day }.fold(BigDecimal.ZERO) { acc, s -> acc + s.amount(YearMonth.from(day)) }

    companion object {
        fun dateIn(s: Scheduled, month: YearMonth): LocalDate = month.atDay(minOf(s.dayOfMonth, month.lengthOfMonth()))
    }
}

/** Deterministic generator: the same persona and seed always give the same history. */
object SyntheticHistories {
    val START: LocalDate = LocalDate.of(2027, 1, 1)
    /** 18 months, through a leap February (2028). */
    val END: LocalDate = LocalDate.of(2028, 6, 30)

    val PERSONAS = listOf(
        Persona("steady", weekdayChance = 0.85, weekendChance = 0.9, weekdayMedian = 350.0, weekendMedian = 600.0),
        Persona("intermittent", weekdayChance = 0.35, weekendChance = 0.5, weekdayMedian = 700.0, weekendMedian = 1_200.0, payDay = PayDay.LAST_WORKING_DAY),
        Persona("spiky", weekdayChance = 0.75, weekendChance = 0.8, weekdayMedian = 300.0, weekendMedian = 500.0, spikesPerMonth = 1.5, payDay = PayDay.SEVENTH, salary = 120_000.0),
        Persona("weekend-heavy", weekdayChance = 0.6, weekendChance = 0.95, weekdayMedian = 150.0, weekendMedian = 2_000.0, payDay = PayDay.LAST_WORKING_DAY, salary = 65_000.0),
        Persona("drifting-festive", weekdayChance = 0.8, weekendChance = 0.85, weekdayMedian = 400.0, weekendMedian = 700.0, driftPerMonth = 0.02, festiveBoost = 1.6, spikesPerMonth = 0.5),
        Persona("freelancer", weekdayChance = 0.7, weekendChance = 0.7, weekdayMedian = 450.0, weekendMedian = 650.0, spikesPerMonth = 0.7, payDay = PayDay.IRREGULAR, salary = 90_000.0)
    )

    val SEEDS = listOf(11L, 23L, 37L, 41L)

    fun all(): List<SyntheticHistory> = PERSONAS.flatMap { p -> SEEDS.map { generate(p, it) } }

    fun generate(p: Persona, seed: Long, start: LocalDate = START, end: LocalDate = END): SyntheticHistory {
        val rnd = SplittableRandom(seed * 1_000_003 + p.name.hashCode())
        val everyday = mutableMapOf<LocalDate, BigDecimal>()
        var day = start
        while (!day.isAfter(end)) {
            val weekend = day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY
            val months = java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(day)).toDouble()
            val level = Math.pow(1 + p.driftPerMonth, months) * (if (day.monthValue in 10..11) p.festiveBoost else 1.0)
            var amount = 0.0
            if (rnd.nextDouble() < (if (weekend) p.weekendChance else p.weekdayChance)) {
                amount += logNormal(rnd, if (weekend) p.weekendMedian else p.weekdayMedian, p.sigma) * level
            }
            if (rnd.nextDouble() < p.spikesPerMonth / day.lengthOfMonth()) amount += p.spikeMin + rnd.nextDouble() * (p.spikeMax - p.spikeMin)
            if (amount > 0) everyday[day] = BigDecimal(amount).setScale(2, RoundingMode.HALF_UP)
            day = day.plusDays(1)
        }
        val electricity = { m: YearMonth -> BigDecimal(1_400 + (m.monthValue % 6) * 260 + (if (m.monthValue in 4..6) 900 else 0)).setScale(2) }
        val schedule = listOf(
            Scheduled(DueSource.BILL, "Rent", 5) { BigDecimal("18000.00") },
            Scheduled(DueSource.MANDATE, "SIP", 10) { BigDecimal("5000.00") },
            Scheduled(DueSource.RENEWAL, "Netflix", 12) { BigDecimal("649.00") },
            Scheduled(DueSource.BILL, "Tata Power", 20, electricity),
            Scheduled(DueSource.RENEWAL, "Spotify", 31) { BigDecimal("119.00") }
        )
        return SyntheticHistory(p, seed, start, end, everyday, schedule, salaries(p, rnd, start, end))
    }

    private fun salaries(p: Persona, rnd: SplittableRandom, start: LocalDate, end: LocalDate): List<IncomeCredit> {
        val out = mutableListOf<IncomeCredit>()
        var month = YearMonth.from(start)
        while (!month.isAfter(YearMonth.from(end))) {
            val (day, amount) = when (p.payDay) {
                PayDay.FIRST -> beforeWeekend(month.atDay(1)) to p.salary
                PayDay.LAST_WORKING_DAY -> beforeWeekend(month.atEndOfMonth()) to p.salary
                PayDay.SEVENTH -> afterWeekend(month.atDay(7)) to p.salary
                PayDay.IRREGULAR -> month.atDay(1 + rnd.nextInt(month.lengthOfMonth())) to p.salary * (0.4 + rnd.nextDouble() * 1.2)
            }
            if (!day.isBefore(start) && !day.isAfter(end)) out += IncomeCredit(day, if (p.payDay == PayDay.IRREGULAR) "Client payments" else "ACME TECHNOLOGIES PVT LTD", BigDecimal(amount).setScale(2, RoundingMode.HALF_UP))
            month = month.plusMonths(1)
        }
        return out.sortedBy { it.day }
    }

    private fun beforeWeekend(d: LocalDate): LocalDate = when (d.dayOfWeek) {
        DayOfWeek.SATURDAY -> d.minusDays(1)
        DayOfWeek.SUNDAY -> d.minusDays(2)
        else -> d
    }

    private fun afterWeekend(d: LocalDate): LocalDate = when (d.dayOfWeek) {
        DayOfWeek.SATURDAY -> d.plusDays(2)
        DayOfWeek.SUNDAY -> d.plusDays(1)
        else -> d
    }

    /** A log-normal amount with the given median (Box–Muller). */
    private fun logNormal(rnd: SplittableRandom, median: Double, sigma: Double): Double {
        val z = sqrt(-2 * ln(1 - rnd.nextDouble())) * kotlin.math.cos(2 * Math.PI * rnd.nextDouble())
        return median * exp(sigma * z)
    }
}
