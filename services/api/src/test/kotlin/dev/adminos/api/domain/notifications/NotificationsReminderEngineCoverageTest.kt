package dev.adminos.api.domain.notifications

import dev.adminos.api.domain.financial.Bill
import dev.adminos.api.domain.financial.BillStatus
import dev.adminos.api.domain.financial.InMemoryBillRepository
import dev.adminos.api.domain.notifications.NotificationsFixtures.prefs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Disabled
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ReminderEngine (AOS-312): overdue marking, reminder days, de-duplication and error isolation. */
class NotificationsReminderEngineCoverageTest {

    private val userId = UUID.randomUUID()
    private val bills = InMemoryBillRepository()
    private val prefsRepo = InMemoryNotificationPreferencesRepository()
    private val notifRepo = InMemoryNotificationRepository()
    private val notifications = notificationService(prefsRepo, notifRepo)
    private val engine = ReminderEngine(bills, prefsRepo, notifications)
    private val today = LocalDate.of(2026, 10, 16)

    private fun bill(dueDate: LocalDate, user: UUID = userId, status: BillStatus = BillStatus.UPCOMING, sent: Set<Int>? = null,
                     sent3d: Boolean = false, sent1d: Boolean = false) =
        Bill(userId = user, billerName = "HDFC Credit Card", amount = BigDecimal("4560.00"), dueDate = dueDate,
             status = status, sentReminderDays = sent, reminderSent3d = sent3d, reminderSent1d = sent1d)

    private suspend fun reminders(user: UUID = userId) = notifRepo.findByUserAndType(user, NotificationType.BILL_REMINDER)

    @Test
    fun `a 3-day reminder is sent with plural wording and recorded on the bill`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId))
        val b = bills.save(bill(today.plusDays(3)))

        engine.processReminder(b, today)

        val sent = reminders().single()
        assertEquals("Bill due in 3 days", sent.title)
        assertEquals("HDFC Credit Card bill of ₹4560.00 is due on 2026-10-19", sent.body)
        assertEquals(mapOf("entity_type" to "bill", "entity_id" to b.id.toString(), "action" to "view_bill"), sent.data)
        val updated = bills.findById(b.id)!!
        assertTrue(updated.reminderSent3d)
        assertFalse(updated.reminderSent1d)
        assertEquals(setOf(3), updated.sentReminderDays)
    }

    @Test
    fun `a 1-day reminder uses singular wording and sets the 1d flag`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId))
        val b = bills.save(bill(today.plusDays(1), sent = setOf(3)))

        engine.processReminder(b, today)

        assertEquals("Bill due in 1 day", reminders().single().title)
        val updated = bills.findById(b.id)!!
        assertTrue(updated.reminderSent1d)
        assertEquals(setOf(3, 1), updated.sentReminderDays)
    }

    @Test
    fun `a custom reminder day is sent without touching the 3d and 1d flags`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId, billReminderDays = listOf(7, 3, 1)))
        val b = bills.save(bill(today.plusDays(7)))

        engine.processReminder(b, today)

        val updated = bills.findById(b.id)!!
        assertEquals(1, reminders().size)
        assertEquals(setOf(7), updated.sentReminderDays)
        assertFalse(updated.reminderSent3d)
        assertFalse(updated.reminderSent1d)
    }

    @Test
    fun `a day already in sentReminderDays is not sent again`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId, billReminderDays = listOf(5)))
        val b = bills.save(bill(today.plusDays(5), sent = setOf(5)))

        engine.processReminder(b, today)

        assertTrue(reminders().isEmpty())
    }

    @Test
    fun `the legacy 3d flag suppresses a repeat 3-day reminder`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId))

        engine.processReminder(bills.save(bill(today.plusDays(3), sent3d = true)), today)

        assertTrue(reminders().isEmpty())
    }

    @Test
    fun `the legacy 1d flag suppresses a repeat 1-day reminder`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId))

        engine.processReminder(bills.save(bill(today.plusDays(1), sent1d = true)), today)

        assertTrue(reminders().isEmpty())
    }

    @Test
    fun `a day that is not a reminder day sends nothing`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId))

        engine.processReminder(bills.save(bill(today.plusDays(2))), today)

        assertTrue(reminders().isEmpty())
    }

    @Test
    fun `a past-due bill gets no reminder`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId, billReminderDays = listOf(1)))

        engine.processReminder(bills.save(bill(today.minusDays(1))), today)

        assertTrue(reminders().isEmpty())
    }

    @Test
    fun `reminders switched off sends nothing`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId, billReminders = false))
        val b = bills.save(bill(today.plusDays(3)))

        engine.processReminder(b, today)

        assertTrue(reminders().isEmpty())
        assertEquals(null, bills.findById(b.id)!!.sentReminderDays)
    }

    @Test
    fun `a user without preferences gets the default 3-day reminder`() = runBlocking<Unit> {
        val b = bills.save(bill(today.plusDays(3)))

        engine.processReminder(b, today)

        assertTrue(reminders().isNotEmpty())
        assertEquals(setOf(3), bills.findById(b.id)!!.sentReminderDays)
    }

    @Test
    fun `markOverdueBills flips past-due bills to overdue and notifies`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId))
        val past = bills.save(bill(LocalDate.now().minusDays(2)))

        engine.markOverdueBills(LocalDate.now())

        assertEquals(BillStatus.OVERDUE, bills.findById(past.id)!!.status)
        val n = notifRepo.findByUserAndType(userId, NotificationType.OVERDUE).single()
        assertEquals("Bill overdue", n.title)
        assertEquals(mapOf("entity_type" to "bill", "entity_id" to past.id.toString()), n.data)
    }

    @Test
    fun `runReminders marks overdue bills and sends due reminders`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId))
        val now = LocalDate.now()
        val overdue = bills.save(bill(now.minusDays(1)))
        val dueIn3 = bills.save(bill(now.plusDays(3)))
        val paid = bills.save(bill(now.plusDays(1), status = BillStatus.PAID))

        val summary = engine.runReminders()

        assertEquals(ReminderRunSummary(overdueMarked = 1, remindersSent = 1, failed = 0), summary)
        assertEquals(BillStatus.OVERDUE, bills.findById(overdue.id)!!.status)
        assertEquals(setOf(3), bills.findById(dueIn3.id)!!.sentReminderDays)
        assertEquals(null, bills.findById(paid.id)!!.sentReminderDays)
        assertEquals(1, reminders().size)
    }

    @Test
    fun `runReminders keeps going when one bill fails`() = runBlocking<Unit> {
        val brokenUser = UUID.randomUUID()
        val failingPrefs = FailingForUserPreferencesRepository(brokenUser)
        val svc = notificationService(failingPrefs, notifRepo)
        val eng = ReminderEngine(bills, failingPrefs, svc)
        failingPrefs.save(prefs(userId))
        bills.save(bill(LocalDate.now().plusDays(3), user = brokenUser))
        val ok = bills.save(bill(LocalDate.now().plusDays(3)))

        val summary = eng.runReminders()

        assertEquals(ReminderRunSummary(overdueMarked = 0, remindersSent = 1, failed = 1), summary)
        assertEquals(setOf(3), bills.findById(ok.id)!!.sentReminderDays)
        assertEquals(1, reminders().size)
    }

    @Test
    fun `runReminders with no bills does nothing`() = runBlocking<Unit> {
        assertEquals(ReminderRunSummary(0, 0, 0), engine.runReminders())

        assertTrue(notifRepo.findPending().isEmpty())
    }

    @Test
    fun `sending the 1-day reminder keeps an earlier 3d flag set`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId))
        val b = bills.save(bill(today.plusDays(1), sent3d = true))

        engine.processReminder(b, today)

        val updated = bills.findById(b.id)!!
        assertTrue(updated.reminderSent3d)
        assertTrue(updated.reminderSent1d)
    }

    @Test
    fun `sending a custom-day reminder keeps an earlier 1d flag set`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId, billReminderDays = listOf(7)))
        val b = bills.save(bill(today.plusDays(7), sent1d = true))

        engine.processReminder(b, today)

        assertTrue(bills.findById(b.id)!!.reminderSent1d)
        assertEquals(1, reminders().size)
    }

    @Test
    fun `a 2-day custom reminder is sent`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId, billReminderDays = listOf(2)))

        engine.processReminder(bills.save(bill(today.plusDays(2), sent = setOf(4))), today)

        assertEquals("Bill due in 2 days", reminders().single().title)
    }

    @Test
    fun `shouldSendReminder sends a 2-day reminder`() {
        assertTrue(ReminderEngine.shouldSendReminder(2, listOf(2), true, true))
    }

    @Test
    fun `shouldSendReminder skips a day already in the sent set`() {
        assertFalse(ReminderEngine.shouldSendReminder(5, listOf(5), false, false, setOf(5)))
    }

    @Test
    fun `shouldSendReminder sends a custom day not yet sent`() {
        assertTrue(ReminderEngine.shouldSendReminder(5, listOf(5, 3), true, true))
    }

    @Test
    fun `duplicate reminder days send a single reminder`() = runBlocking<Unit> {
        prefsRepo.save(prefs(userId, billReminderDays = listOf(3, 3)))

        engine.processReminder(bills.save(bill(today.plusDays(3))), today)

        assertEquals(1, reminders().size)
    }
}
