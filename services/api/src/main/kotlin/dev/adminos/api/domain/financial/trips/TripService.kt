package dev.adminos.api.domain.financial.trips

import dev.adminos.api.domain.common.TimeRange
import dev.adminos.api.domain.common.sumMoney
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionRepository
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.financial.links.LinkKind
import dev.adminos.api.domain.financial.links.LinkRelation
import dev.adminos.api.domain.financial.links.TransactionLinkRepository
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Why a trip edit was refused. Routes map these to HTTP. */
sealed class TripError(val reason: String) : RuntimeException(reason) {
    class TripNotFound : TripError("Trip not found")
    class TransactionNotFound : TripError("Transaction not found")
    class NotASpend : TripError("Only a spend can be part of a trip")
}

data class TripYear(val year: Int, val total: BigDecimal, val trips: List<Trip>)

data class TripDetail(val trip: Trip, val transactions: Map<UUID, Transaction>)

/**
 * Trips (AOS-910, D-90): computed on read by [TripGrouper] from the user's transactions, refunds,
 * bookings and edits, so they always agree with the feed. Only the edits are stored.
 */
class TripService(
    private val transactions: TransactionRepository,
    private val links: TransactionLinkRepository,
    private val bookings: TravelBookingRepository,
    private val edits: TripEditRepository,
    private val grouper: TripGrouper = TripGrouper(),
    private val clock: Clock = Clock.systemUTC()
) {
    private data class Loaded(val trips: List<Trip>, val transactions: Map<UUID, Transaction>)

    companion object {
        /** How far back trips are computed; far beyond any year the page shows. */
        private val HISTORY: Duration = Duration.ofDays(3 * 366)
        private const val MAX_ROWS = 20_000
        private const val MAX_CANDIDATES = 50
        private const val CANDIDATE_DAYS = 2L
    }

    suspend fun year(userId: UUID, year: Int, zone: ZoneId): TripYear {
        val trips = load(userId, zone).trips.filter { it.start.year == year }
        return TripYear(year, trips.map { it.total }.sumMoney(), trips)
    }

    suspend fun detail(userId: UUID, tripId: UUID, zone: ZoneId): TripDetail {
        val loaded = load(userId, zone)
        val trip = loaded.trips.firstOrNull { it.id == tripId } ?: throw TripError.TripNotFound()
        return TripDetail(trip, trip.members.mapNotNull { loaded.transactions[it.transactionId] }.associateBy { it.id })
    }

    /** Spends near the trip that aren't in it, newest first: what the user may want to add. */
    suspend fun candidates(userId: UUID, tripId: UUID, zone: ZoneId): List<Transaction> {
        val loaded = load(userId, zone)
        val trip = loaded.trips.firstOrNull { it.id == tripId } ?: throw TripError.TripNotFound()
        val inTrips = loaded.trips.flatMap { t -> t.members.map { it.transactionId } }.toSet()
        val from = trip.start.minusDays(CANDIDATE_DAYS)
        val to = trip.end.plusDays(CANDIDATE_DAYS)
        return loaded.transactions.values.filter { t ->
            t.id !in inTrips && t.type == TransactionType.DEBIT && t.nature == TransactionNature.SPEND &&
                t.transactedAt.atZone(zone).toLocalDate().let { !it.isBefore(from) && !it.isAfter(to) }
        }.sortedByDescending { it.transactedAt }.take(MAX_CANDIDATES)
    }

    suspend fun rename(userId: UUID, tripId: UUID, name: String, zone: ZoneId): Trip {
        val clean = name.trim()
        require(clean.length in 1..TripEdit.MAX_NAME) { "name: 1..${TripEdit.MAX_NAME} characters" }
        require(clean.none { it.isISOControl() }) { "name: no control characters" }
        return edit(userId, tripId, zone) { it.copy(name = clean) }
    }

    suspend fun dismiss(userId: UUID, tripId: UUID, zone: ZoneId) {
        edit(userId, tripId, zone, mustRemain = false) { it.copy(dismissed = true) }
    }

    suspend fun add(userId: UUID, tripId: UUID, transactionId: UUID, zone: ZoneId): Trip {
        val t = transactions.findById(transactionId)?.takeIf { it.userId == userId } ?: throw TripError.TransactionNotFound()
        if (t.type != TransactionType.DEBIT || t.nature != TransactionNature.SPEND) throw TripError.NotASpend()
        return edit(userId, tripId, zone) { it.copy(added = it.added + transactionId, removed = it.removed - transactionId) }
    }

    suspend fun remove(userId: UUID, tripId: UUID, transactionId: UUID, zone: ZoneId): Trip {
        val trip = detail(userId, tripId, zone).trip
        if (trip.members.none { it.transactionId == transactionId }) throw TripError.TransactionNotFound()
        return edit(userId, tripId, zone) { it.copy(added = it.added - transactionId, removed = it.removed + transactionId) }
    }

    private suspend fun edit(userId: UUID, tripId: UUID, zone: ZoneId, mustRemain: Boolean = true, change: (TripEdit) -> TripEdit): Trip {
        val trip = detail(userId, tripId, zone).trip
        val current = edits.findByUser(userId).firstOrNull { it.tripId == tripId } ?: TripEdit(tripId, userId)
        edits.save(change(current))
        if (!mustRemain) return trip
        return detail(userId, tripId, zone).trip
    }

    private suspend fun load(userId: UUID, zone: ZoneId): Loaded {
        val now = clock.instant()
        val range = TimeRange(now.minus(HISTORY), now.plus(Duration.ofDays(1)))
        val all = transactions.findByUserId(userId, from = range.from, to = range.until, limit = MAX_ROWS)
        val byId = all.associateBy { it.id }
        val input = TripInput(all, refunds(userId, byId), bookings.findByUser(userId, range), edits.findByUser(userId), zone)
        return Loaded(grouper.group(input), byId)
    }

    /** Refunded so far per debit, from the refund links the pipeline applied (D-40). */
    private suspend fun refunds(userId: UUID, byId: Map<UUID, Transaction>): Map<UUID, BigDecimal> =
        links.findByUser(userId, LinkKind.MERGED, LinkRelation.REFUND).mapNotNull { link ->
            val pair = listOfNotNull(link.transactionId, link.relatedTransactionId).mapNotNull(byId::get)
            val debit = pair.firstOrNull { it.type == TransactionType.DEBIT } ?: return@mapNotNull null
            val credit = pair.firstOrNull { it.nature == TransactionNature.REFUND } ?: return@mapNotNull null
            debit.id to credit.amount
        }.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.sumMoney() }

    /** The user's own date, for the default year. */
    fun today(zone: ZoneId): LocalDate = LocalDate.now(clock.withZone(zone))
}
