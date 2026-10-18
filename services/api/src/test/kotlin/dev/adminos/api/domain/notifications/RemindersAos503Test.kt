package dev.adminos.api.domain.notifications

import dev.adminos.api.domain.financial.Bill
import dev.adminos.api.domain.financial.BillStatus
import dev.adminos.api.domain.financial.BillingCycle
import dev.adminos.api.domain.financial.InMemoryBillRepository
import dev.adminos.api.domain.financial.InMemorySubscriptionRepository
import dev.adminos.api.domain.financial.Subscription
import dev.adminos.api.domain.financial.SubscriptionRepository
import dev.adminos.api.domain.financial.SubscriptionStatus
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserCalendar
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-503: reminders on the user's own dates, days sent stored, renewals reminded once per date. */
class RemindersAos503Test {
    /** 12:00 UTC on 16 Oct = 17:30 IST on the 16th = 02:00 on the 17th in Kiritimati (UTC+14) = 02:00 on the 16th in Honolulu (UTC−10). */
    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneId.of("UTC"))
    private val users = InMemoryUserRepository()
    private val calendar = UserCalendar(users, clock)
    private val bills = InMemoryBillRepository()
    private val prefs = InMemoryNotificationPreferencesRepository()
    private val notifications = InMemoryNotificationRepository()
    private val sender = notificationService(prefs, notifications, clock = clock)
    private val engine = ReminderEngine(bills, prefs, sender, calendar)

    private suspend fun user(zone: String = "Asia/Kolkata", days: List<Int> = listOf(3, 1), on: Boolean = true): UUID {
        val u = users.save(User(email = "u-${UUID.randomUUID()}@example.com", googleId = "g-${UUID.randomUUID()}", timezone = zone))
        prefs.save(NotificationPreferences(userId = u.id, billReminderDays = days, billReminders = on))
        return u.id
    }

    private fun bill(owner: UUID, due: LocalDate, name: String = "Tata Power") =
        Bill(userId = owner, billerName = name, amount = BigDecimal("2350.00"), dueDate = due)

    // ── UserCalendar ──

    @Test
    fun `today is the user's own date, with India time when unknown or invalid`() = runBlocking<Unit> {
        assertEquals(LocalDate.of(2026, 10, 17), calendar.today(user("Pacific/Kiritimati")))
        assertEquals(LocalDate.of(2026, 10, 16), calendar.today(user("Pacific/Honolulu")))
        assertEquals(ZoneId.of("Asia/Kolkata"), calendar.zoneOf(UUID.randomUUID()))
        assertEquals(ZoneId.of("Asia/Kolkata"), calendar.zoneOf(user("Not/AZone")))
        assertEquals(LocalDate.of(2026, 10, 16), calendar.serverToday())
    }

    // ── Bill reminders ──

    @Test
    fun `a custom reminder day is sent once, and the stored days stop a repeat`() = runBlocking<Unit> {
        val u = user(days = listOf(14, 7))
        val b = bills.save(bill(u, LocalDate.of(2026, 10, 30))) // 14 days ahead: beyond the old 7-day look-ahead
        assertEquals(1, engine.runReminders().remindersSent)
        assertEquals(setOf(14), bills.findById(b.id)!!.sentReminderDays)
        assertEquals(0, engine.runReminders().remindersSent, "same day again")
        assertEquals("Bill due in 14 days", notifications.findByUserAndType(u, NotificationType.BILL_REMINDER).single().title)
    }

    @Test
    fun `the days-before count uses the user's date`() = runBlocking<Unit> {
        val ahead = user("Pacific/Kiritimati")
        bills.save(bill(ahead, LocalDate.of(2026, 10, 18))) // 1 day ahead for them (it's the 17th there), 2 for the server
        assertEquals(1, engine.runReminders().remindersSent)
        assertEquals("Bill due in 1 day", notifications.findByUserAndType(ahead, NotificationType.BILL_REMINDER).single().title)
    }

    @Test
    fun `overdue means overdue for the user, not the server`() = runBlocking<Unit> {
        val ahead = user("Pacific/Kiritimati")
        val behind = user("Pacific/Honolulu")
        val dueToday = bills.save(bill(ahead, LocalDate.of(2026, 10, 16))) // server: due today; Kiritimati: yesterday
        val dueYesterday = bills.save(bill(behind, LocalDate.of(2026, 10, 15))) // Honolulu: the 16th, so the 15th has passed
        val notYet = bills.save(bill(behind, LocalDate.of(2026, 10, 16))) // Honolulu: today, not overdue
        assertEquals(2, engine.runReminders().overdueMarked)
        assertEquals(BillStatus.OVERDUE, bills.findById(dueToday.id)!!.status)
        assertEquals(BillStatus.OVERDUE, bills.findById(dueYesterday.id)!!.status)
        assertEquals(BillStatus.UPCOMING, bills.findById(notYet.id)!!.status)
    }

    // ── Renewal reminders ──

    private val subscriptions = InMemorySubscriptionRepository()
    private val renewals = RenewalReminders(subscriptions, prefs, sender, calendar)

    private fun sub(owner: UUID, next: LocalDate?, status: SubscriptionStatus = SubscriptionStatus.ACTIVE, name: String = "Netflix") =
        Subscription(userId = owner, name = name, merchantName = "netflix", amount = BigDecimal("649.00"), billingCycle = BillingCycle.MONTHLY,
            nextBillingDate = next, status = status)

    @Test
    fun `a renewal is reminded on the reminder days, once per date`() = runBlocking<Unit> {
        val u = user()
        val s = subscriptions.save(sub(u, LocalDate.of(2026, 10, 19)))
        assertEquals(RenewalRunSummary(1, 0), renewals.run())
        val n = notifications.findByUserAndType(u, NotificationType.BILL_DUE).single()
        assertEquals("Netflix renews in 3 days", n.title)
        assertEquals("₹649.00 expected on 2026-10-19, based on your past payments.", n.body)
        assertEquals(mapOf("entity_type" to "subscription", "entity_id" to s.id.toString(), "action" to "view_subscription"), n.data)
        val stored = subscriptions.findById(s.id)!!
        assertEquals(LocalDate.of(2026, 10, 19), stored.renewalRemindedFor)
        assertEquals(setOf(3), stored.renewalReminderDaysSent)
        assertEquals(RenewalRunSummary(0, 0), renewals.run(), "not twice")
    }

    @Test
    fun `a new renewal date starts a fresh set of reminders`() = runBlocking<Unit> {
        val u = user()
        val s = subscriptions.save(sub(u, LocalDate.of(2026, 10, 19)).copy(renewalRemindedFor = LocalDate.of(2026, 9, 19), renewalReminderDaysSent = setOf(3, 1)))
        assertTrue(renewals.remind(s, LocalDate.of(2026, 10, 16)))
        assertEquals(setOf(3), subscriptions.findById(s.id)!!.renewalReminderDaysSent)
    }

    @Test
    fun `a trial says it ends, one day is singular`() = runBlocking<Unit> {
        val u = user()
        val s = sub(u, LocalDate.of(2026, 10, 17), SubscriptionStatus.TRIAL, name = "")
        assertTrue(renewals.remind(subscriptions.save(s), LocalDate.of(2026, 10, 16)))
        assertEquals("Free trial of netflix ends in 1 day", notifications.findByUserAndType(u, NotificationType.BILL_DUE).single().title)
        assertEquals("A subscription renews in 3 days", RenewalReminders.title(s.copy(merchantName = null, status = SubscriptionStatus.ACTIVE), 3))
    }

    @Test
    fun `no reminder when cancelled, undated, off, or not a reminder day`() = runBlocking<Unit> {
        val today = LocalDate.of(2026, 10, 16)
        val u = user()
        assertFalse(renewals.remind(sub(u, today.plusDays(3), SubscriptionStatus.CANCELLED), today))
        assertFalse(renewals.remind(sub(u, today.plusDays(3), SubscriptionStatus.PAUSED), today))
        assertFalse(renewals.remind(sub(u, null), today))
        assertFalse(renewals.remind(sub(u, today.plusDays(2)), today))
        assertFalse(renewals.remind(sub(user(on = false), today.plusDays(3)), today))
        assertFalse(renewals.remind(sub(UUID.randomUUID(), today.plusDays(5)), today), "defaults are 3 and 1 days")
        assertTrue(notifications.findPending().isEmpty())
    }

    @Test
    fun `one failing subscription doesn't stop the others`() = runBlocking<Unit> {
        val u = user()
        val bad = subscriptions.save(sub(u, LocalDate.of(2026, 10, 19), name = "Broken"))
        subscriptions.save(sub(u, LocalDate.of(2026, 10, 19), name = "Spotify"))
        val failing = object : SubscriptionRepository by subscriptions {
            override suspend fun update(subscription: Subscription): Subscription =
                if (subscription.id == bad.id) throw IOException("write failed") else subscriptions.update(subscription)
        }
        assertEquals(RenewalRunSummary(1, 1), RenewalReminders(failing, prefs, sender, calendar).run())
    }
}
