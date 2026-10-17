package dev.adminos.api.domain.financial.trips

import dev.adminos.api.domain.common.TimeRange
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** In-memory [TravelBookingRepository]: the same merge rules as the Postgres upsert (V52). */
class InMemoryTravelBookingRepository : TravelBookingRepository {
    private val store = ConcurrentHashMap<Triple<UUID, String, String>, TravelBooking>()

    override suspend fun upsert(booking: TravelBooking): TravelBooking {
        val key = Triple(booking.userId, booking.brand, booking.refHash)
        val merged = store[key]?.let { known ->
            known.copy(
                journeyDate = known.journeyDate ?: booking.journeyDate,
                amount = known.amount ?: booking.amount,
                bookedAt = minOf(known.bookedAt, booking.bookedAt),
                cancelled = known.cancelled || booking.cancelled
            )
        } ?: booking
        store[key] = merged
        return merged
    }

    override suspend fun findByUser(userId: UUID, range: TimeRange): List<TravelBooking> =
        store.values.filter { it.userId == userId && it.bookedAt in range }.sortedBy { it.bookedAt }
}

class InMemoryTripEditRepository : TripEditRepository {
    private val store = ConcurrentHashMap<UUID, TripEdit>()

    override suspend fun findByUser(userId: UUID): List<TripEdit> = store.values.filter { it.userId == userId }

    override suspend fun save(edit: TripEdit): TripEdit {
        store[edit.tripId] = edit
        return edit
    }
}
