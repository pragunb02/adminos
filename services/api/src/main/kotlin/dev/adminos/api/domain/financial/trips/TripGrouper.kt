package dev.adminos.api.domain.financial.trips

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.financial.matching.PaymentClaim
import dev.adminos.api.domain.financial.matching.PaymentMatch
import dev.adminos.api.domain.financial.matching.PaymentMatcher
import dev.adminos.api.domain.financial.matching.PaymentWindow
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/** Travel brands (merchant-catalogue names) and how they travel; agencies sell any mode (null). Data. */
object TravelBrands {
    val MODES: Map<String, TravelMode?> = mapOf(
        "indigo" to TravelMode.FLIGHT, "air india" to TravelMode.FLIGHT, "akasa air" to TravelMode.FLIGHT,
        "irctc" to TravelMode.TRAIN, "redbus" to TravelMode.BUS,
        "makemytrip" to null, "goibibo" to null, "cleartrip" to null
    )

    fun isTravel(merchant: String?): Boolean = merchant?.trim()?.lowercase() in MODES.keys
    fun modeOf(merchant: String?): TravelMode? = MODES[merchant?.trim()?.lowercase()]
}

/** What the grouper reads: the user's transactions, refunds linked to them, bookings and edits. */
data class TripInput(
    val transactions: List<Transaction>,
    /** Refunded so far per debit (D-40 links). */
    val refunds: Map<UUID, BigDecimal>,
    val bookings: List<TravelBooking>,
    val edits: List<TripEdit>,
    val zone: ZoneId
)

/**
 * Groups travel into trips (AOS-910, D-90). Pure and deterministic: the same input gives the same trips.
 * 1. **Legs:** each travel booking on its journey date: from its booking email or SMS when one is tied
 *    to it (PaymentMatcher, D-88), else its booking date; a booking email without a payment is a leg too.
 *    A booking refunded in full, or cancelled, is not a leg.
 * 2. **Chain:** legs within [TripRules.chainGapDays] of the previous one are one trip (outbound and return).
 * 3. **Span:** first to last leg; a single leg spans its day and the next.
 * 4. **Spends around:** debits that are spending, in the span, in [TripRules.around] categories.
 * 5. **Edits win:** added transactions belong to that trip only, removed ones never return, dismissed trips are gone.
 */
class TripGrouper(private val rules: TripRules = TripRules(), private val matcher: PaymentMatcher = PaymentMatcher()) {

    fun group(input: TripInput): List<Trip> {
        val spending = input.transactions.filter { it.type == TransactionType.DEBIT && it.nature == TransactionNature.SPEND }
        val byId = spending.associateBy { it.id }
        val legs = legs(input, spending)
        val edits = input.edits.associateBy { it.tripId }
        val claimedByUser = input.edits.filterNot { it.dismissed }.flatMap { e -> e.added.map { it to e.tripId } }.toMap()

        val trips = chain(legs).map { chained ->
            val id = tripId(chained.first())
            val start = chained.first().date
            val end = if (chained.size == 1) start.plusDays(1) else chained.last().date
            val edit = edits[id]
            val bookingIds = chained.mapNotNull { it.transactionId }.toSet()
            val around = spending.filter { t ->
                t.id !in bookingIds && t.category in rules.around && !TravelBrands.isTravel(t.merchantName) &&
                    t.transactedAt.atZone(input.zone).toLocalDate().let { !it.isBefore(start) && !it.isAfter(end) }
            }
            val auto = bookingIds.mapNotNull(byId::get).map { member(it, TripRole.BOOKING, input) } +
                around.map { member(it, TripRole.SPEND, input) }
            val added = edit?.added.orEmpty().mapNotNull(byId::get).map { member(it, TripRole.ADDED, input) }
            val members = (auto.filter { m -> claimedByUser[m.transactionId].let { it == null || it == id } } + added)
                .distinctBy { it.transactionId }
                .filterNot { it.transactionId in edit?.removed.orEmpty() }
            Trip(id, edit?.name ?: defaultName(start, end), edit?.name != null, start, end, chained, members)
        }
        return trips.filterNot { edits[it.id]?.dismissed == true }.sortedBy { it.start }
    }

    private fun member(t: Transaction, role: TripRole, input: TripInput) =
        TripMember(t.id, role, t.amount, (input.refunds[t.id] ?: BigDecimal.ZERO).min(t.amount))

    /** Booking transactions and booking documents, each on its journey date. */
    private fun legs(input: TripInput, spending: List<Transaction>): List<TripLeg> {
        val travel = spending.filter { TravelBrands.isTravel(it.merchantName) && it.paymentMethod != PaymentMethod.CASH }
        val live = input.bookings.filter { !it.cancelled && it.journeyDate != null }
        val cancelledPayments = input.bookings.filter { it.cancelled }.mapNotNull { b -> paymentOf(b, travel, emptySet())?.id }.toSet()
        val tied = mutableMapOf<UUID, TravelBooking>()
        for (b in live.sortedBy { it.bookedAt }) {
            paymentOf(b, travel, tied.keys)?.let { tied[it.id] = b }
        }
        val fromPayments = travel.filter { t -> t.id !in cancelledPayments && (input.refunds[t.id] ?: BigDecimal.ZERO) < t.amount }.map { t ->
            val booking = tied[t.id]
            TripLeg(booking?.journeyDate ?: t.transactedAt.atZone(input.zone).toLocalDate(), t.merchantName.orEmpty(),
                booking?.mode ?: TravelBrands.modeOf(t.merchantName), t.id, booking?.id)
        }
        val tiedBookings = tied.values.map { it.id }.toSet()
        val withoutPayment = live.filter { it.id !in tiedBookings }.map { b ->
            TripLeg(requireNotNull(b.journeyDate), b.brand, b.mode, null, b.id)
        }
        return (fromPayments + withoutPayment).sortedWith(compareBy<TripLeg>({ it.date }, { it.transactionId ?: it.bookingId }))
    }

    /** The payment a booking email or SMS describes: exact amount, booked minutes before, paid to the brand (D-88). */
    private fun paymentOf(b: TravelBooking, travel: List<Transaction>, taken: Set<UUID>): Transaction? {
        val amount = b.amount ?: return null
        val claim = PaymentClaim(b.userId, listOf(amount), b.bookedAt, WINDOW, b.brand)
        return when (val d = matcher.decide(claim, travel, excluded = taken)) {
            is PaymentMatch.Auto -> d.payment
            else -> null
        }
    }

    private fun chain(legs: List<TripLeg>): List<List<TripLeg>> {
        val out = mutableListOf<MutableList<TripLeg>>()
        for (leg in legs) {
            val current = out.lastOrNull()
            if (current != null && ChronoUnit.DAYS.between(current.last().date, leg.date) <= rules.chainGapDays) current += leg
            else out += mutableListOf(leg)
        }
        return out
    }

    companion object {
        /** A booking email arrives within minutes of the payment; a statement row the same or next day. */
        private val WINDOW = PaymentWindow(before = Duration.ofMinutes(30), after = Duration.ofHours(6), days = -1..1, cardDays = -1..3)
        private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
        private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

        /** A stable ID from the first leg, so edits stay with their trip when it regroups. */
        fun tripId(first: TripLeg): UUID = UUID.nameUUIDFromBytes("trip:${first.transactionId ?: first.bookingId}".toByteArray())

        fun defaultName(start: LocalDate, end: LocalDate): String =
            if (start.year == end.year) "Trip, ${start.format(DAY_MONTH)} – ${end.format(DAY_MONTH_YEAR)}"
            else "Trip, ${start.format(DAY_MONTH_YEAR)} – ${end.format(DAY_MONTH_YEAR)}"
    }
}
