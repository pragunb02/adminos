package dev.adminos.api.domain.notifications

import dev.adminos.api.domain.financial.Bill
import dev.adminos.api.domain.financial.BillRepository
import dev.adminos.api.domain.financial.BillStatus
import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.domain.scheduling.BatchCounts
import dev.adminos.api.domain.scheduling.forEachIsolated
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Bill reminders (AOS-501, AOS-503): marks overdue bills, then reminds on each of the user's
 * reminder days (default 3 and 1 days before), each day once — the days already sent are stored
 * with the bill (V21), so a restart or a second run never repeats one. Dates are the user's own
 * ([UserCalendar]).
 */
class ReminderEngine(
    private val billRepository: BillRepository,
    private val preferencesRepository: NotificationPreferencesRepository,
    private val notificationService: NotificationService,
    private val calendar: UserCalendar
) {
    private val logger = LoggerFactory.getLogger(ReminderEngine::class.java)

    /** Marks overdue bills, then sends due reminders. One bill's failure never stops the others. */
    suspend fun runReminders(): ReminderRunSummary {
        val overdue = markOverdueBills()
        // A day's slack either side of the server's date; each bill is then judged on its user's date.
        val upcomingBills = billRepository.findUpcoming(calendar.serverToday(), MAX_REMINDER_DAYS + 1)
        var sent = 0
        val counts = forEachIsolated(upcomingBills, logger, { "reminder for bill ${it.id}" }) { bill ->
            sent += processReminder(bill, calendar.today(bill.userId))
        }
        return ReminderRunSummary(overdueMarked = overdue.succeeded, remindersSent = sent, failed = overdue.failed + counts.failed)
    }

    /** @return how many reminders were sent for [bill] on [today] (the user's date) */
    internal suspend fun processReminder(bill: Bill, today: LocalDate): Int {
        val daysUntilDue = ChronoUnit.DAYS.between(today, bill.dueDate).toInt()
        if (daysUntilDue < 0) return 0

        val prefs = preferencesRepository.findByUserId(bill.userId)
            ?: NotificationPreferences.defaultFor(bill.userId)

        if (!prefs.billReminders) return 0

        // Each day once, and check against the bill as updated by this run (AOS-613: [3,3] sent twice).
        var current = bill
        var sent = 0
        for (reminderDay in prefs.billReminderDays.distinct()) {
            if (daysUntilDue == reminderDay && !isReminderSent(current, reminderDay)) {
                notificationService.send(
                    userId = bill.userId,
                    type = NotificationType.BILL_REMINDER,
                    title = "Bill due in $reminderDay day${if (reminderDay > 1) "s" else ""}",
                    body = "${bill.billerName} bill of ₹${bill.amount} is due on ${bill.dueDate}",
                    data = mapOf(
                        "entity_type" to "bill",
                        "entity_id" to bill.id.toString(),
                        "action" to "view_bill"
                    ),
                    userTimezone = calendar.zoneOf(bill.userId).id
                )
                current = billRepository.update(current.copy(
                    reminderSent3d = current.reminderSent3d || reminderDay == 3,
                    reminderSent1d = current.reminderSent1d || reminderDay == 1,
                    sentReminderDays = (current.sentReminderDays ?: emptySet()) + reminderDay
                ))
                sent++
                logger.info("Sent {}d reminder for bill {}", reminderDay, bill.id)
            }
        }
        return sent
    }

    /**
     * Marks bills overdue whose due date has passed **for their user**. Candidates come from a
     * server-date query widened by a day; bills not yet overdue in the user's zone are left alone.
     */
    internal suspend fun markOverdueBills(): BatchCounts {
        val serverToday = calendar.serverToday()
        val candidates = (billRepository.findOverdue(serverToday) + billRepository.findUpcoming(serverToday, 1)).distinctBy { it.id }
        var marked = 0
        val counts = forEachIsolated(candidates, logger, { "overdue bill ${it.id}" }) { bill ->
            if (!bill.dueDate.isBefore(calendar.today(bill.userId))) return@forEachIsolated
            billRepository.update(bill.copy(status = BillStatus.OVERDUE))
            notificationService.send(
                userId = bill.userId,
                type = NotificationType.OVERDUE,
                title = "Bill overdue",
                body = "${bill.billerName} bill of ₹${bill.amount} was due on ${bill.dueDate}",
                data = mapOf("entity_type" to "bill", "entity_id" to bill.id.toString()),
                userTimezone = calendar.zoneOf(bill.userId).id
            )
            marked++
        }
        return BatchCounts(succeeded = marked, failed = counts.failed)
    }

    // The stored set covers every day; the 3d/1d flags are kept for bills reminded before V21.
    private fun isReminderSent(bill: Bill, days: Int): Boolean {
        if (bill.sentReminderDays?.contains(days) == true) return true
        return when (days) {
            3 -> bill.reminderSent3d
            1 -> bill.reminderSent1d
            else -> false
        }
    }

    companion object {
        /** The furthest "days before" reminder looked for: preferences allow 1–30 (it used to look only 7 ahead). */
        const val MAX_REMINDER_DAYS = 30

        fun shouldSendReminder(
            daysUntilDue: Int,
            reminderDays: List<Int>,
            reminderSent3d: Boolean,
            reminderSent1d: Boolean,
            sentReminderDays: Set<Int> = emptySet()
        ): Boolean {
            if (daysUntilDue !in reminderDays) return false
            if (daysUntilDue in sentReminderDays) return false
            return when (daysUntilDue) {
                3 -> !reminderSent3d
                1 -> !reminderSent1d
                else -> true
            }
        }
    }
}

/** What one reminder run did (counts only). */
data class ReminderRunSummary(val overdueMarked: Int, val remindersSent: Int, val failed: Int)
