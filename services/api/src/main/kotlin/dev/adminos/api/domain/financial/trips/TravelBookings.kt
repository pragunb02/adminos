package dev.adminos.api.domain.financial.trips

import dev.adminos.api.domain.ingestion.sms.TravelBookingNotice
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Records a booking read from an SMS (AOS-910); the Gmail worker records email bookings with the same key. */
class TravelBookings(private val repository: TravelBookingRepository) {

    suspend fun record(userId: UUID, notice: TravelBookingNotice, receivedAt: Instant, sourceRef: String): TravelBooking =
        repository.upsert(TravelBooking(
            userId = userId, source = BookingSource.SMS, sourceRef = sourceRef, brand = notice.brand, mode = notice.mode,
            refHash = refHash(notice.brand, notice.pnr), journeyDate = notice.journeyDate, bookedAt = receivedAt,
            amount = notice.amount, cancelled = notice.cancelled
        ))

    companion object {
        /** SHA-256 of brand and PNR, as the Go worker computes it (bookings.Booking.RefHash). */
        fun refHash(brand: String, pnr: String): String =
            MessageDigest.getInstance("SHA-256").digest("${brand.lowercase()}:${pnr.trim().uppercase()}".toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
