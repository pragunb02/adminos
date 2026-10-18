package dev.adminos.api.domain.financial.trips

import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.financial.links.InMemoryTransactionLinkRepository
import dev.adminos.api.domain.financial.links.LinkKind
import dev.adminos.api.domain.financial.links.LinkRelation
import dev.adminos.api.domain.financial.links.TransactionLink
import dev.adminos.api.domain.ingestion.sms.TravelBookingNotice
import dev.adminos.api.domain.ingestion.sms.TravelBookingSmsParser
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-910: trips computed on read, with refunds, bookings by SMS and the user's edits. */
class TripServiceTest {
    private val user = UUID.randomUUID()
    private val ist = ZoneId.of("Asia/Kolkata")
    private val txns = InMemoryTransactionRepository()
    private val links = InMemoryTransactionLinkRepository()
    private val bookings = InMemoryTravelBookingRepository()
    private val edits = InMemoryTripEditRepository()
    private val clock = Clock.fixed(Instant.parse("2026-11-01T06:00:00Z"), ZoneOffset.UTC)
    private val service = TripService(txns, links, bookings, edits, clock = clock)

    private suspend fun save(at: String, merchant: String, amount: String, category: TransactionCategory = TransactionCategory.TRANSPORT, userId: UUID = user) =
        txns.save(Transaction(userId = userId, type = TransactionType.DEBIT, amount = BigDecimal(amount), merchantName = merchant, category = category,
            paymentMethod = PaymentMethod.UPI, transactedAt = Instant.parse(at)))

    @Test
    fun `the year's trips and total, with a linked refund netted`() = runBlocking<Unit> {
        val ticket = save("2026-10-10T03:00:00Z", "IndiGo", "5000.00")
        save("2026-10-10T06:00:00Z", "Uber", "400.00")
        val refund = txns.save(Transaction(userId = user, type = TransactionType.CREDIT, nature = TransactionNature.REFUND, amount = BigDecimal("1000.00"),
            merchantName = "IndiGo", transactedAt = Instant.parse("2026-10-12T03:00:00Z")))
        links.save(TransactionLink(userId = user, transactionId = ticket.id, relatedTransactionId = refund.id, source = "sms", kind = LinkKind.MERGED,
            relation = LinkRelation.REFUND, score = 9, reason = "refund", rulesVersion = "x"))
        save("2025-12-01T03:00:00Z", "IRCTC", "700.00")
        save("2026-10-10T03:00:00Z", "IndiGo", "9999.00", userId = UUID.randomUUID())

        val y = service.year(user, 2026, ist)
        assertEquals(BigDecimal("4400.00"), y.total)
        assertEquals(1, y.trips.size)
        assertEquals(1, service.year(user, 2025, ist).trips.size)
        assertEquals(2026, service.today(ist).year)
    }

    @Test
    fun `an IRCTC SMS places the trip on its journey date`() = runBlocking<Unit> {
        save("2026-09-01T03:31:00Z", "IRCTC", "845.00")
        val notice = TravelBookingSmsParser.parse("PNR:4512345678,TRN:12627,DOJ:24-10-26,SL,DP:19:20,Fare:845.00,SC Rs17.70+PG CHGS. -IRCTC", LocalDate.of(2026, 9, 1))
        TravelBookings(bookings).record(user, assertIs<TravelBookingNotice>(notice), Instant.parse("2026-09-01T03:30:00Z"), "sms-1")
        assertEquals(LocalDate.of(2026, 10, 24), service.year(user, 2026, ist).trips.single().start)
    }

    @Test
    fun `detail, candidates and every edit, and errors for what isn't the user's`() = runBlocking<Unit> {
        val ticket = save("2026-10-10T03:00:00Z", "IndiGo", "5000.00")
        val cab = save("2026-10-10T06:00:00Z", "Uber", "400.00")
        val hotel = save("2026-10-10T15:00:00Z", "Sample Stay", "3000.00", TransactionCategory.OTHER)
        val far = save("2026-09-01T15:00:00Z", "Sample Shop", "300.00", TransactionCategory.SHOPPING)
        val theirs = save("2026-10-10T07:00:00Z", "Uber", "300.00", userId = UUID.randomUUID())
        val refund = txns.save(Transaction(userId = user, type = TransactionType.CREDIT, amount = BigDecimal("10.00"), transactedAt = Instant.parse("2026-10-10T07:00:00Z")))
        val id = service.year(user, 2026, ist).trips.single().id

        assertEquals(setOf(ticket.id, cab.id), service.detail(user, id, ist).transactions.keys)
        assertEquals(listOf(hotel.id), service.candidates(user, id, ist).map { it.id })
        assertEquals("Wedding", service.rename(user, id, "  Wedding  ", ist).name)
        assertTrue(service.add(user, id, hotel.id, ist).members.any { it.transactionId == hotel.id })
        assertTrue(service.remove(user, id, cab.id, ist).members.none { it.transactionId == cab.id })
        assertEquals(BigDecimal("8000.00"), service.detail(user, id, ist).trip.total)

        assertFailsWith<TripError.TripNotFound> { service.detail(UUID.randomUUID(), id, ist) }
        assertFailsWith<TripError.TripNotFound> { service.candidates(user, UUID.randomUUID(), ist) }
        assertFailsWith<TripError.TransactionNotFound> { service.add(user, id, theirs.id, ist) }
        assertFailsWith<TripError.TransactionNotFound> { service.add(user, id, UUID.randomUUID(), ist) }
        assertFailsWith<TripError.NotASpend> { service.add(user, id, refund.id, ist) }
        assertFailsWith<TripError.TransactionNotFound> { service.remove(user, id, far.id, ist) }
        assertFailsWith<IllegalArgumentException> { service.rename(user, id, " ", ist) }
        assertFailsWith<IllegalArgumentException> { service.rename(user, id, "Bad\u0000name", ist) }
        assertFailsWith<IllegalArgumentException> { service.rename(user, id, "x".repeat(81), ist) }

        service.dismiss(user, id, ist)
        assertTrue(service.year(user, 2026, ist).trips.isEmpty())
        assertFailsWith<TripError.TripNotFound> { service.dismiss(user, id, ist) }
    }

    @Test
    fun `IRCTC SMS - booking, cancellation, and what is never read`() {
        val on = LocalDate.of(2026, 9, 1)
        val b = assertIs<TravelBookingNotice>(TravelBookingSmsParser.parse("PNR:4512345678,TRN:12627,DOJ:24-10-2026,Fare:1,234.50 -IRCTC", on))
        assertEquals(LocalDate.of(2026, 10, 24), b.journeyDate)
        assertEquals(BigDecimal("1234.50"), b.amount)
        val c = assertIs<TravelBookingNotice>(TravelBookingSmsParser.parse("PNR 4512345678 has been cancelled. Refund Rs 600 will be credited. -IRCTC", on))
        assertTrue(c.cancelled && c.amount == null)
        assertNull(TravelBookingSmsParser.parse("Rs 845 debited from A/c XX1234 at IRCTC", on), "a bank alert has no PNR")
        assertNull(TravelBookingSmsParser.parse("PNR:4512345678,DOJ:24-10-26 for your bus", on), "not IRCTC")
        assertNull(TravelBookingSmsParser.parse("PNR:4512345678,TRN:12627,DOJ:31-02-26 -IRCTC", on), "no such date")
        assertNull(TravelBookingSmsParser.parse("PNR:4512345678,TRN:12627,DOJ:24-10-29 -IRCTC", on), "more than 400 days away")
        assertNull(TravelBookingSmsParser.parse("PNR:4512345678,TRN:12627,DOJ:24-10-26 -IRCTC" + "x".repeat(2000), on), "oversized")
        assertNull(assertIs<TravelBookingNotice>(TravelBookingSmsParser.parse("PNR:4512345678,TRN:12627,DOJ:24-10-26,Fare:9999999 -IRCTC", on)).amount, "an implausible fare is dropped")
        assertFailsWith<IllegalArgumentException> { TravelBookingNotice("IRCTC", TravelMode.TRAIN, "12", null, null, false) }
    }
}
