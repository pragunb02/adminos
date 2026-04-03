package dev.adminos.api.domain.notifications

import dev.adminos.api.domain.financial.Bill
import dev.adminos.api.domain.financial.BillRepository
import dev.adminos.api.domain.financial.BillStatus
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Reminder engine — cron service that checks bills due within 7 days
 * and sends reminders at user-configured days before due date.
 *
 * Fix #1: findBillsDueWithin now delegates to billRepository.findUpcoming()
 * Fix #2: Custom reminder days tracked via sentReminderDays set in bill metadata
 */
class ReminderEngine(
    private val billRepository: BillRepository,
    private val preferencesRepository: NotificationPreferencesRepository,
    private val notificationService: NotificationService
) {
    private val logger = LoggerFactory.getLogger(ReminderEngine::class.java)

    suspend fun runReminders() {
        val today = LocalDate.now()
        markOverdueBills(today)

        // Fix #1: delegate to repository instead of returning empty list
        val upcomingBills = billRepository.findUpcoming(7)

        for (bill in upcomingBills) {
            try {
                processReminder(bill, today)
            } catch (e: Exception) {
                logger.error("Failed to process reminder for bill {}: {}", bill.id, e.message)
            }
        }
    }

    internal suspend fun processReminder(bill: Bill, today: LocalDate) {
        val daysUntilDue = ChronoUnit.DAYS.between(today, bill.dueDate).toInt()
        if (daysUntilDue < 0) return

        val prefs = preferencesRepository.findByUserId(bill.userId)
            ?: NotificationPreferences.defaultFor(bill.userId)

        if (!prefs.billReminders) return

        for (reminderDay in prefs.billReminderDays) {
            if (daysUntilDue == reminderDay && !isReminderSent(bill, reminderDay)) {
                notificationService.send(
                    userId = bill.userId,
                    type = NotificationType.BILL_REMINDER,
                    title = "Bill due in $reminderDay day${if (reminderDay > 1) "s" else ""}",
                    body = "${bill.billerName} bill of ₹${bill.amount} is due on ${bill.dueDate}",
                    data = mapOf(
                        "entity_type" to "bill",
                        "entity_id" to bill.id.toString(),
                        "action" to "view_bill"
                    )
                )

                // Fix #2: Track ALL reminder days, not just 3 and 1
                val updatedSentDays = (bill.sentReminderDays ?: emptySet()) + reminderDay
                val updated = bill.copy(
                    reminderSent3d = bill.reminderSent3d || reminderDay == 3,
                    reminderSent1d = bill.reminderSent1d || reminderDay == 1,
                    sentReminderDays = updatedSentDays
                )
                billRepository.update(updated)
                logger.info("Sent {}d reminder for bill {} ({})", reminderDay, bill.id, bill.billerName)
            }
        }
    }

    internal suspend fun markOverdueBills(today: LocalDate) {
        val overdueBills = billRepository.findOverdue()
        for (bill in overdueBills) {
            billRepository.update(bill.copy(status = BillStatus.OVERDUE))
            notificationService.send(
                userId = bill.userId,
                type = NotificationType.OVERDUE,
                title = "Bill overdue",
                body = "${bill.billerName} bill of ₹${bill.amount} was due on ${bill.dueDate}",
                data = mapOf("entity_type" to "bill", "entity_id" to bill.id.toString())
            )
        }
    }

    // Fix #2: Check sentReminderDays set for custom days, fall back to flags for 3/1
    private fun isReminderSent(bill: Bill, days: Int): Boolean {
        if (bill.sentReminderDays?.contains(days) == true) return true
        return when (days) {
            3 -> bill.reminderSent3d
            1 -> bill.reminderSent1d
            else -> false
        }
    }

    companion object {
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
