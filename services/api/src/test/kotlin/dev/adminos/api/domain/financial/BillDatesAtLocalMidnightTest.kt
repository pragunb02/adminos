package dev.adminos.api.domain.financial

import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserCalendar
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AOS-638: bill dates follow the user's own calendar ([UserCalendar]) on an injected clock, never the
 * JVM's zone. Each test sits a minute either side of a user's local midnight. The clocks carry the UTC
 * zone on purpose: only the user's zone may decide the date.
 */
class BillDatesAtLocalMidnightTest {

    private val users = InMemoryUserRepository()
    private val india = runBlocking { users.save(User(email = "in@example.com", googleId = "g-in", timezone = "Asia/Kolkata")) }
    private val newYork = runBlocking { users.save(User(email = "ny@example.com", googleId = "g-ny", timezone = "America/New_York")) }
    private val kiritimati = runBlocking { users.save(User(email = "ki@example.com", googleId = "g-ki", timezone = "Pacific/Kiritimati")) }

    private val oct16 = LocalDate.of(2026, 10, 16)
    private val oct17 = LocalDate.of(2026, 10, 17)

    /** 23:59 on 16 Oct in India (18:29 UTC). */
    private val beforeIndiaMidnight = Instant.parse("2026-10-16T18:29:00Z")
    /** 00:01 on 17 Oct in India (18:31 UTC); New York is still on 16 Oct afternoon. */
    private val afterIndiaMidnight = Instant.parse("2026-10-16T18:31:00Z")

    private val bills = InMemoryBillRepository()

    private fun serviceAt(instant: Instant) =
        BillTrackingService(bills, InMemoryTransactionRepository(), UserCalendar(users, Clock.fixed(instant, ZoneOffset.UTC)))

    private fun bill(user: User, due: LocalDate) = runBlocking {
        bills.save(Bill(userId = user.id, billerName = "Tata Power", amount = BigDecimal("2350.00"), dueDate = due))
    }

    @Test
    fun `a minute before India midnight a bill due that day is still upcoming`() = runBlocking<Unit> {
        val dueToday = bill(india, oct16)
        assertEquals(listOf(dueToday.id), serviceAt(beforeIndiaMidnight).getUpcomingBills(india.id).map { it.id })
    }

    @Test
    fun `a minute after India midnight yesterday's bill drops out and tomorrow's is in`() = runBlocking<Unit> {
        bill(india, oct16)
        val dueNow = bill(india, oct17)
        assertEquals(listOf(dueNow.id), serviceAt(afterIndiaMidnight).getUpcomingBills(india.id).map { it.id })
    }

    @Test
    fun `a user in New York keeps their own date after India has moved on`() = runBlocking<Unit> {
        val dueToday = bill(newYork, oct16)
        assertEquals(listOf(dueToday.id), serviceAt(afterIndiaMidnight).getUpcomingBills(newYork.id).map { it.id })
    }

    @Test
    fun `upcoming bills end exactly at the window's last day in the user's zone`() = runBlocking<Unit> {
        val last = bill(india, oct17.plusDays(7))
        bill(india, oct17.plusDays(8))
        assertEquals(listOf(last.id), serviceAt(afterIndiaMidnight).getUpcomingBills(india.id, withinDays = 7).map { it.id })
    }

    @Test
    fun `a bill becomes overdue a minute after its user's midnight, not before`() = runBlocking<Unit> {
        val due = bill(india, oct16)
        assertTrue(serviceAt(beforeIndiaMidnight).markOverdueBills().isEmpty())
        assertEquals(listOf(due.id), serviceAt(afterIndiaMidnight).markOverdueBills().map { it.id })
        assertEquals(BillStatus.OVERDUE, bills.findById(due.id)?.status)
    }

    @Test
    fun `India's midnight does not make a New York bill overdue`() = runBlocking<Unit> {
        val due = bill(newYork, oct16)
        assertTrue(serviceAt(afterIndiaMidnight).markOverdueBills().isEmpty())
        assertEquals(BillStatus.UPCOMING, bills.findById(due.id)?.status)
        // 00:01 on 17 Oct in New York (04:01 UTC)
        assertEquals(listOf(due.id), serviceAt(Instant.parse("2026-10-17T04:01:00Z")).markOverdueBills().map { it.id })
    }

    @Test
    fun `a user ahead of the server's date is judged on their own tomorrow`() = runBlocking<Unit> {
        // 00:01 on 17 Oct in Kiritimati (UTC+14) is 15:31 on 16 Oct in India: the server's date is still the 16th.
        val due = bill(kiritimati, oct16)
        assertEquals(listOf(due.id), serviceAt(Instant.parse("2026-10-16T10:01:00Z")).markOverdueBills().map { it.id })
    }
}
