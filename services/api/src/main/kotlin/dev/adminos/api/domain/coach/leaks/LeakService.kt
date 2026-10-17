package dev.adminos.api.domain.coach.leaks

import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.coach.lenses.LensMatcher
import dev.adminos.api.domain.coach.lenses.LensService
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionRepository
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The transactions behind a leaks number, newest first, and how many there are in all. */
data class LeakTransactions(val items: List<Transaction>, val total: Int)

/**
 * "Where it leaks" (AOS-902): gathers a range's counted transactions, puts each in its lenses (AOS-901,
 * with the owner's overrides) and hands them to [LeakCalculator]. The other coach features (limits,
 * digest, report card, goals) read the same [facts], so every number agrees.
 */
class LeakService(
    private val transactions: TransactionRepository,
    private val lenses: LensService,
    private val clock: Clock = Clock.systemUTC()
) {
    val matcher: LensMatcher get() = lenses.matcher

    /** This month so far, in the user's zone (like the console, D-45). */
    fun defaultRange(zone: ZoneId): DateRange {
        val today = LocalDate.now(clock.withZone(zone))
        return DateRange(today.withDayOfMonth(1), today)
    }

    fun validate(range: DateRange) {
        require(!range.to.isBefore(range.from)) { "to: must be on or after from" }
        require(range.days <= MAX_DAYS) { "from: a range can cover at most $MAX_DAYS days" }
    }

    suspend fun report(userId: UUID, range: DateRange, zone: ZoneId): LeakReport {
        validate(range)
        val previous = range.previous()
        val all = facts(userId, DateRange(previous.from, range.to), zone)
        val (now, before) = all.partition { !it.local.toLocalDate().isBefore(range.from) }
        return LeakCalculator.report(LeakCalculator.Input(range, matcher.lenses, matcher.catalogue.lateNight, now, before))
    }

    /** The transactions a leaks number is made of, newest first; at most [limit] of them, with the full count. */
    suspend fun transactions(userId: UUID, range: DateRange, zone: ZoneId, filter: LeakFilter, limit: Int): LeakTransactions {
        validate(range)
        require(limit in 1..MAX_LIST) { "limit: 1..$MAX_LIST" }
        val window = matcher.catalogue.lateNight
        val matching = facts(userId, range, zone).filter { filter.accepts(it, window) }
            .sortedWith(compareByDescending<LeakFact> { it.transaction.transactedAt }.thenByDescending { it.transaction.id })
        return LeakTransactions(matching.take(limit).map { it.transaction }, matching.size)
    }

    /**
     * Every spend and refund of [userId] whose local day (in [zone]) is in [range], with its lenses.
     * Transactions in no lens are kept (with no lenses), so callers can tell "nothing leaked" from "no data".
     */
    suspend fun facts(userId: UUID, range: DateRange, zone: ZoneId): List<LeakFact> {
        val overrides = lenses.overridesOf(userId)
        val start = range.from.atStartOfDay(zone).toInstant()
        // Inclusive end a microsecond before the next local midnight (Postgres keeps microseconds); refined by local day below.
        val end = range.to.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1_000)
        return transactions.findByUserId(userId, from = start, to = end, limit = Int.MAX_VALUE)
            .filter { it.nature in LensMatcher.COUNTED }
            .map { t ->
                val local = t.transactedAt.atZone(zone).toLocalDateTime()
                LeakFact(t, matcher.lensesOf(t, overrides), local, timeKnown = t.sourceType != DATE_ONLY_SOURCE, person = matcher.isPersonToPerson(t))
            }
            .filter { !it.local.toLocalDate().isBefore(range.from) && !it.local.toLocalDate().isAfter(range.to) }
    }

    companion object {
        /** Like the console's analytics (AOS-301). */
        const val MAX_DAYS = 3 * 366L
        const val MAX_LIST = 1000

        /** Statement rows have a date, not a time (stored at local midday), so they say nothing about late night. */
        const val DATE_ONLY_SOURCE = "statement"
    }
}
