package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.common.TimeRange
import dev.adminos.api.domain.financial.*
import dev.adminos.api.domain.financial.trips.*
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AOS-910 on Postgres (V52): bookings merge like the worker's upsert; edits round-trip; a trip is computed from both. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TripsIntegrationTest {
    private val bookings = ExposedTravelBookingRepository()
    private val edits = ExposedTripEditRepository()
    private val txns = ExposedTransactionRepository()

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    @Test
    fun `the PNR hash matches the Go worker's, bookings merge, edits round-trip`() = runBlocking<Unit> {
        // The same vector is asserted in services/workers/internal/bookings: SHA-256("indigo:AB12CD").
        assertEquals("8b7ce9dd76109096cde382c5181d29d625e769f1f27339117aaa6385b5f00700", TravelBookings.refHash("IndiGo", " ab12cd "))
        val user = PostgresTestDatabase.newUser()
        val bookedAt = Instant.now().minus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        val hash = TravelBookings.refHash("IndiGo", "AB12CD")
        bookings.upsert(TravelBooking(userId = user, source = BookingSource.GMAIL, sourceRef = "m1", brand = "IndiGo", mode = TravelMode.FLIGHT,
            refHash = hash, journeyDate = LocalDate.now().plusDays(5), bookedAt = bookedAt, amount = BigDecimal("5432.00")))
        val merged = bookings.upsert(TravelBooking(userId = user, source = BookingSource.SMS, sourceRef = "m2", brand = "IndiGo", mode = TravelMode.FLIGHT,
            refHash = hash, journeyDate = null, bookedAt = bookedAt.plusSeconds(3600), amount = null, cancelled = true))
        assertTrue(merged.cancelled)
        val stored = bookings.findByUser(user, TimeRange(bookedAt.minusSeconds(1), Instant.now())).single()
        assertEquals(BigDecimal("5432.00"), stored.amount)
        assertEquals(bookedAt, stored.bookedAt)
        assertTrue(stored.cancelled)

        val edit = TripEdit(java.util.UUID.randomUUID(), user, name = "Home", added = setOf(java.util.UUID.randomUUID()), removed = setOf(java.util.UUID.randomUUID()))
        edits.save(edit)
        edits.save(edit.copy(dismissed = true, added = emptySet()))
        assertEquals(listOf(edit.copy(dismissed = true, added = emptySet())), edits.findByUser(user))
    }

    @Test
    fun `a trip is computed from Postgres transactions and bookings`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val zone = ZoneId.of("Asia/Kolkata")
        val bookedAt = Instant.now().minus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        val journey = LocalDate.now(zone).minusDays(3)
        txns.save(Transaction(userId = user, type = TransactionType.DEBIT, amount = BigDecimal("845.00"), merchantName = "IRCTC",
            category = TransactionCategory.TRANSPORT, paymentMethod = PaymentMethod.UPI, transactedAt = bookedAt.plusSeconds(60)))
        txns.save(Transaction(userId = user, type = TransactionType.DEBIT, amount = BigDecimal("300.00"), merchantName = "Uber",
            category = TransactionCategory.TRANSPORT, paymentMethod = PaymentMethod.UPI, transactedAt = journey.atTime(8, 0).atZone(zone).toInstant()))
        bookings.upsert(TravelBooking(userId = user, source = BookingSource.SMS, sourceRef = "s1", brand = "IRCTC", mode = TravelMode.TRAIN,
            refHash = TravelBookings.refHash("IRCTC", "4512345678"), journeyDate = journey, bookedAt = bookedAt, amount = BigDecimal("845.00")))
        val service = TripService(txns, ExposedTransactionLinkRepository(), bookings, edits)
        val trip = service.year(user, journey.year, zone).trips.single()
        assertEquals(journey, trip.start)
        assertEquals(BigDecimal("1145.00"), trip.total)
    }
}
