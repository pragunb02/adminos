package dev.adminos.api.domain.financial.trips

import dev.adminos.api.domain.common.TimeRange
import dev.adminos.api.domain.financial.TransactionCategory
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class TravelMode { FLIGHT, TRAIN, BUS }

enum class BookingSource { GMAIL, SMS }

/**
 * A travel booking read from an email or SMS (AOS-910, V52): the brand, mode, journey date and amount.
 * The PNR is only a hash ([refHash]); no names, seats or stations (D-33).
 */
data class TravelBooking(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val source: BookingSource,
    val sourceRef: String,
    val brand: String,
    val mode: TravelMode,
    val refHash: String,
    val journeyDate: LocalDate?,
    val bookedAt: Instant,
    val amount: BigDecimal?,
    val cancelled: Boolean = false
) {
    init {
        require(brand.isNotBlank() && brand.length <= 40) { "brand must be 1..40 characters" }
        require(refHash.matches(Regex("[0-9a-f]{64}"))) { "refHash must be a SHA-256 hex digest" }
        require(amount == null || amount.signum() > 0) { "amount must be positive" }
        require(sourceRef.isNotBlank() && sourceRef.length <= 255) { "sourceRef must be 1..255 characters" }
    }
}

interface TravelBookingRepository {
    /** Stores a booking, or merges it into the known one (same user, brand and PNR hash): blanks filled, a cancellation cancels. */
    suspend fun upsert(booking: TravelBooking): TravelBooking
    /** A user's bookings made in [range]. */
    suspend fun findByUser(userId: UUID, range: TimeRange): List<TravelBooking>
}

/** The user's edits to one trip (AOS-910): kept apart, so regrouping never loses them. */
data class TripEdit(
    val tripId: UUID,
    val userId: UUID,
    val name: String? = null,
    val dismissed: Boolean = false,
    val added: Set<UUID> = emptySet(),
    val removed: Set<UUID> = emptySet()
) {
    init {
        require(name == null || name.length in 1..MAX_NAME) { "a trip name is 1..$MAX_NAME characters" }
        require((added intersect removed).isEmpty()) { "a transaction can't be both added and removed" }
    }

    companion object {
        const val MAX_NAME = 80
    }
}

interface TripEditRepository {
    suspend fun findByUser(userId: UUID): List<TripEdit>
    suspend fun save(edit: TripEdit): TripEdit
}

/**
 * The grouping rule's parameters (AOS-910, D-90), as data. [chainGapDays] joins an outbound and its
 * return; [around] are the categories of the spends around a trip (cabs, food at stations and airports).
 */
data class TripRules(
    val chainGapDays: Long = 7,
    val around: Set<TransactionCategory> = setOf(TransactionCategory.TRANSPORT, TransactionCategory.FOOD)
) {
    init {
        require(chainGapDays in 0..60) { "chainGapDays must be 0..60" }
    }
}

/** Why a transaction is in a trip. */
enum class TripRole { BOOKING, SPEND, ADDED }

data class TripMember(val transactionId: UUID, val role: TripRole, val amount: BigDecimal, val refunded: BigDecimal)

/** One leg: a booking on its journey date. */
data class TripLeg(val date: LocalDate, val brand: String, val mode: TravelMode?, val transactionId: UUID?, val bookingId: UUID?)

data class Trip(
    val id: UUID,
    val name: String,
    val named: Boolean,
    val start: LocalDate,
    val end: LocalDate,
    val legs: List<TripLeg>,
    val members: List<TripMember>
) {
    val total: BigDecimal get() = members.fold(BigDecimal.ZERO) { acc, m -> acc + m.amount - m.refunded }
    val modes: List<TravelMode> get() = legs.mapNotNull { it.mode }.distinct()
}
