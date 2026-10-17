package dev.adminos.api.domain.notifications

import dev.adminos.api.domain.financial.Subscription
import dev.adminos.api.domain.financial.SubscriptionRepository
import dev.adminos.api.domain.financial.SubscriptionStatus
import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.domain.scheduling.forEachIsolated
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Renewal reminders (AOS-503): subscriptions renew (or a free trial ends) on the user's bill-reminder
 * days before the expected date. Each (renewal date, day) is sent once — stored on the subscription
 * (V21); a new renewal date starts a fresh set. The date is *expected* (from past payments), and
 * the message says so.
 */
class RenewalReminders(
    private val subscriptions: SubscriptionRepository,
    private val preferences: NotificationPreferencesRepository,
    private val notifications: NotificationService,
    private val calendar: UserCalendar
) {
    private val logger = LoggerFactory.getLogger(RenewalReminders::class.java)

    suspend fun run(): RenewalRunSummary {
        val today = calendar.serverToday()
        // A day's slack either side; each subscription is then judged on its user's date.
        val renewing = subscriptions.findRenewingBetween(today.minusDays(1), today.plusDays(ReminderEngine.MAX_REMINDER_DAYS + 1L))
        var sent = 0
        val counts = forEachIsolated(renewing, logger, { "renewal reminder for subscription ${it.id}" }) { s ->
            if (remind(s, calendar.today(s.userId))) sent++
        }
        return RenewalRunSummary(remindersSent = sent, failed = counts.failed)
    }

    /** @return true if a reminder was sent for [subscription] on [today] (the user's date) */
    internal suspend fun remind(subscription: Subscription, today: LocalDate): Boolean {
        val renewsOn = subscription.nextBillingDate ?: return false
        if (subscription.status != SubscriptionStatus.ACTIVE && subscription.status != SubscriptionStatus.TRIAL) return false
        val days = ChronoUnit.DAYS.between(today, renewsOn).toInt()
        val prefs = preferences.findByUserId(subscription.userId) ?: NotificationPreferences.defaultFor(subscription.userId)
        if (!prefs.billReminders || days !in prefs.billReminderDays) return false
        val alreadySent = if (subscription.renewalRemindedFor == renewsOn) subscription.renewalReminderDaysSent else emptySet()
        if (days in alreadySent) return false

        notifications.send(
            userId = subscription.userId,
            type = NotificationType.BILL_DUE,
            title = title(subscription, days),
            body = "₹${subscription.amount.toPlainString()} expected on $renewsOn, based on your past payments.",
            data = mapOf("entity_type" to "subscription", "entity_id" to subscription.id.toString(), "action" to "view_subscription"),
            userTimezone = calendar.zoneOf(subscription.userId).id
        )
        subscriptions.update(subscription.copy(renewalRemindedFor = renewsOn, renewalReminderDaysSent = alreadySent + days))
        logger.info("Sent {}d renewal reminder for subscription {}", days, subscription.id)
        return true
    }

    companion object {
        internal fun title(s: Subscription, days: Int): String {
            val name = s.name.ifBlank { s.merchantName ?: "A subscription" }
            val whenText = if (days == 1) "in 1 day" else "in $days days"
            return if (s.status == SubscriptionStatus.TRIAL) "Free trial of $name ends $whenText" else "$name renews $whenText"
        }
    }
}

/** What one renewal-reminder run did (counts only). */
data class RenewalRunSummary(val remindersSent: Int, val failed: Int)
