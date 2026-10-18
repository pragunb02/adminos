package dev.adminos.api.domain.financial.trips

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** AOS-910: the trip grouping rule, one behaviour per test. */
class TripGrouperTest {
    private val user = UUID.randomUUID()
    private val ist = ZoneId.of("Asia/Kolkata")
    private val grouper = TripGrouper()

    private fun at(s: String): Instant = OffsetDateTime.parse("${s}+05:30").toInstant()
    private fun txn(at: String, merchant: String, amount: String = "100.00", category: TransactionCategory = TransactionCategory.TRANSPORT, source: String = "sms") =
        Transaction(userId = user, sourceType = source, type = TransactionType.DEBIT, amount = BigDecimal(amount), merchantName = merchant,
            category = category, paymentMethod = PaymentMethod.UPI, transactedAt = at(at))
    private fun booking(brand: String, booked: String, journey: String?, amount: String?, cancelled: Boolean = false, pnr: String = UUID.randomUUID().toString()) =
        TravelBooking(userId = user, source = BookingSource.GMAIL, sourceRef = "m-$pnr", brand = brand, mode = TravelMode.FLIGHT,
            refHash = TravelBookings.refHash(brand, pnr), journeyDate = journey?.let(LocalDate::parse), bookedAt = at(booked),
            amount = amount?.let(::BigDecimal), cancelled = cancelled)
    private fun group(txns: List<Transaction>, bookings: List<TravelBooking> = emptyList(), refunds: Map<UUID, BigDecimal> = emptyMap(),
                      edits: List<TripEdit> = emptyList(), zone: ZoneId = ist) = grouper.group(TripInput(txns, refunds, bookings, edits, zone))

    @Test
    fun `a booking email moves the leg to its journey date and spends around it join`() {
        val ticket = txn("2026-09-20T10:00", "IndiGo", "5432.00")
        val cab = txn("2026-10-24T05:00", "Uber", "450.00")
        val dinner = txn("2026-10-25T20:00", "Swiggy", "600.00", TransactionCategory.FOOD)
        val shopping = txn("2026-10-25T15:00", "Myntra", "999.00", TransactionCategory.SHOPPING)
        val trip = group(listOf(ticket, cab, dinner, shopping), listOf(booking("IndiGo", "2026-09-20T10:01", "2026-10-24", "5432.00"))).single()
        assertEquals(LocalDate.of(2026, 10, 24), trip.start)
        assertEquals(LocalDate.of(2026, 10, 25), trip.end, "a single leg spans its day and the next")
        assertEquals(setOf(ticket.id, cab.id, dinner.id), trip.members.map { it.transactionId }.toSet())
        assertEquals(BigDecimal("6482.00"), trip.total)
        assertEquals(listOf(TravelMode.FLIGHT), trip.modes)
        assertEquals("Trip, 24 Oct – 25 Oct 2026", trip.name)
    }

    @Test
    fun `outbound and return within a week are one trip, further apart two`() {
        val out = txn("2026-03-01T07:00", "IRCTC")
        val back = txn("2026-03-08T07:00", "IRCTC")
        assertEquals(1, group(listOf(out, back)).size)
        assertEquals(2, group(listOf(out, txn("2026-03-09T07:00", "IRCTC"))).size)
        assertEquals(TravelMode.TRAIN, group(listOf(out)).single().legs.single().mode)
    }

    @Test
    fun `a booking refunded in full or cancelled is no trip, a partial refund keeps it and nets the total`() {
        val full = txn("2026-06-01T10:00", "IndiGo", "6000.00")
        assertTrue(group(listOf(full), refunds = mapOf(full.id to BigDecimal("6000.00"))).isEmpty())
        val cancelledByEmail = txn("2026-06-05T10:00", "IndiGo", "4000.00")
        assertTrue(group(listOf(cancelledByEmail), listOf(booking("IndiGo", "2026-06-05T10:01", "2026-06-20", "4000.00", cancelled = true))).isEmpty())
        val partial = txn("2026-07-01T10:00", "Air India", "5000.00")
        val trip = group(listOf(partial), refunds = mapOf(partial.id to BigDecimal("1500.00"))).single()
        assertEquals(BigDecimal("3500.00"), trip.total)
        assertEquals(BigDecimal("1500.00"), trip.members.single().refunded)
    }

    @Test
    fun `a booking email without a payment is a leg, and the same amount twice is told apart by time`() {
        val wallet = booking("redBus", "2026-07-01T10:00", "2026-07-10", "900.00")
        val trip = group(listOf(txn("2026-07-10T20:00", "Rapido")), listOf(wallet)).single()
        assertEquals(LocalDate.of(2026, 7, 10), trip.start)
        assertEquals(1, trip.members.size, "the cab; the booking had no bank payment")
        val first = txn("2026-08-01T09:01", "IRCTC", "845.00")
        val second = txn("2026-08-02T20:01", "IRCTC", "845.00")
        val t = group(listOf(first, second), listOf(booking("IRCTC", "2026-08-01T09:00", "2026-08-15", "845.00"), booking("IRCTC", "2026-08-02T20:00", "2026-08-18", "845.00"))).single()
        assertEquals(LocalDate.of(2026, 8, 15) to LocalDate.of(2026, 8, 18), t.start to t.end)
    }

    @Test
    fun `days are the user's own`() {
        val ticket = txn("2026-04-12T08:00", "Akasa Air")
        val lateNight = txn("2026-04-11T23:50", "Ola")
        val afterMidnight = txn("2026-04-12T00:10", "Uber")
        val members = group(listOf(ticket, lateNight, afterMidnight)).single().members.map { it.transactionId }
        assertTrue(afterMidnight.id in members && lateNight.id !in members)
        val inUtc = group(listOf(ticket, lateNight, afterMidnight), zone = ZoneId.of("UTC")).single().members.map { it.transactionId }
        assertTrue(afterMidnight.id !in inUtc, "00:10 in India is still 11 April in UTC: the zone decides")
    }

    @Test
    fun `only spending counts, never transfers, credits or cash bookings`() {
        val ticket = txn("2026-05-10T08:00", "IndiGo")
        val transfer = txn("2026-05-10T09:00", "Uber").copy(nature = TransactionNature.TRANSFER)
        val credit = txn("2026-05-10T09:00", "Uber").copy(type = TransactionType.CREDIT, nature = TransactionNature.INCOME)
        assertEquals(listOf(ticket.id), group(listOf(ticket, transfer, credit)).single().members.map { it.transactionId })
        assertTrue(group(listOf(ticket.copy(paymentMethod = PaymentMethod.CASH))).isEmpty())
    }

    @Test
    fun `edits win - rename, add to one trip only, remove for good, dismiss`() {
        val a = txn("2026-02-01T08:00", "IndiGo")
        val b = txn("2026-02-20T08:00", "IndiGo")
        val hotel = txn("2026-02-01T20:00", "Sample Stay", "3000.00", TransactionCategory.OTHER)
        val cab = txn("2026-02-01T10:00", "Uber")
        val first = group(listOf(a, b, hotel, cab)).first()
        val edit = TripEdit(first.id, user, name = "Home for the wedding", added = setOf(hotel.id), removed = setOf(cab.id))
        val edited = group(listOf(a, b, hotel, cab), edits = listOf(edit)).first()
        assertEquals("Home for the wedding", edited.name)
        assertTrue(edited.named)
        assertEquals(setOf(a.id, hotel.id), edited.members.map { it.transactionId }.toSet())
        assertEquals(TripRole.ADDED, edited.members.first { it.transactionId == hotel.id }.role)

        val second = group(listOf(a, b)).last()
        val moved = group(listOf(a, b, cab), edits = listOf(TripEdit(second.id, user, added = setOf(cab.id)))).associateBy { it.id }
        assertTrue(cab.id !in moved.getValue(first.id).members.map { it.transactionId }, "an added transaction belongs to that trip only")
        assertTrue(cab.id in moved.getValue(second.id).members.map { it.transactionId })

        assertEquals(listOf(second.id), group(listOf(a, b), edits = listOf(TripEdit(first.id, user, dismissed = true))).map { it.id })
    }

    @Test
    fun `trip ids are stable across regrouping, and edits validate themselves`() {
        val a = txn("2026-02-01T08:00", "IndiGo")
        val id = group(listOf(a)).single().id
        assertEquals(id, group(listOf(a, txn("2026-02-03T08:00", "Uber"), txn("2026-02-04T08:00", "IndiGo"))).single().id)
        assertFailsWith<IllegalArgumentException> { TripEdit(id, user, name = "") }
        assertFailsWith<IllegalArgumentException> { TripEdit(id, user, name = "x".repeat(81)) }
        assertFailsWith<IllegalArgumentException> { TripEdit(id, user, added = setOf(a.id), removed = setOf(a.id)) }
        assertFailsWith<IllegalArgumentException> { TripRules(chainGapDays = 61) }
        assertEquals("Trip, 30 Dec 2026 – 2 Jan 2027", TripGrouper.defaultName(LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 2)))
    }

    @Test
    fun `travel brands and modes are data`() {
        assertTrue(TravelBrands.isTravel(" IndiGo "))
        assertEquals(TravelMode.BUS, TravelBrands.modeOf("redBus"))
        assertEquals(null, TravelBrands.modeOf("MakeMyTrip"))
        assertTrue(!TravelBrands.isTravel(null) && !TravelBrands.isTravel("Uber"))
        val mmt = group(listOf(txn("2026-01-10T08:00", "MakeMyTrip"))).single()
        assertTrue(mmt.modes.isEmpty(), "an agency sells any mode")
        assertFailsWith<IllegalArgumentException> { booking("IndiGo", "2026-01-10T08:00", null, "0") }
        assertFailsWith<IllegalArgumentException> { TravelBooking(userId = user, source = BookingSource.SMS, sourceRef = "x", brand = "IndiGo", mode = TravelMode.FLIGHT,
            refHash = "short", journeyDate = null, bookedAt = Instant.now(), amount = null) }
    }
}
