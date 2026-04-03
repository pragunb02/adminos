package dev.adminos.api.domain.notifications

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReminderEngineTest {

    // ── Reminder Timing Tests ──

    @Test
    fun `should send reminder at 3 days before due when not yet sent`() {
        val result = ReminderEngine.shouldSendReminder(
            daysUntilDue = 3,
            reminderDays = listOf(3, 1),
            reminderSent3d = false,
            reminderSent1d = false
        )
        assertTrue(result)
    }

    @Test
    fun `should send reminder at 1 day before due when not yet sent`() {
        val result = ReminderEngine.shouldSendReminder(
            daysUntilDue = 1,
            reminderDays = listOf(3, 1),
            reminderSent3d = false,
            reminderSent1d = false
        )
        assertTrue(result)
    }

    @Test
    fun `should not send 3d reminder when already sent`() {
        val result = ReminderEngine.shouldSendReminder(
            daysUntilDue = 3,
            reminderDays = listOf(3, 1),
            reminderSent3d = true,
            reminderSent1d = false
        )
        assertFalse(result)
    }

    @Test
    fun `should not send 1d reminder when already sent`() {
        val result = ReminderEngine.shouldSendReminder(
            daysUntilDue = 1,
            reminderDays = listOf(3, 1),
            reminderSent3d = false,
            reminderSent1d = true
        )
        assertFalse(result)
    }

    @Test
    fun `should not send reminder at non-configured day`() {
        val result = ReminderEngine.shouldSendReminder(
            daysUntilDue = 5,
            reminderDays = listOf(3, 1),
            reminderSent3d = false,
            reminderSent1d = false
        )
        assertFalse(result)
    }

    @Test
    fun `should not send reminder at 0 days (due today)`() {
        val result = ReminderEngine.shouldSendReminder(
            daysUntilDue = 0,
            reminderDays = listOf(3, 1),
            reminderSent3d = false,
            reminderSent1d = false
        )
        assertFalse(result)
    }

    @Test
    fun `custom reminder days are respected`() {
        val result = ReminderEngine.shouldSendReminder(
            daysUntilDue = 7,
            reminderDays = listOf(7, 3, 1),
            reminderSent3d = false,
            reminderSent1d = false
        )
        assertTrue(result)
    }
}
