package dev.adminos.api.domain.scheduling

import dev.adminos.api.domain.financial.InMemoryBillRepository
import dev.adminos.api.domain.financial.InMemorySubscriptionRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.SubscriptionDetectionJob
import dev.adminos.api.domain.financial.SubscriptionDetectorService
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionRepository
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.notifications.BillRemindersJob
import dev.adminos.api.domain.notifications.DropOffNudgeService
import dev.adminos.api.domain.notifications.InMemoryDeviceRepository
import dev.adminos.api.domain.notifications.InMemoryNotificationPreferencesRepository
import dev.adminos.api.domain.notifications.InMemoryNotificationRepository
import dev.adminos.api.domain.notifications.NotificationService
import dev.adminos.api.domain.notifications.OnboardingNudgesJob
import dev.adminos.api.domain.notifications.notificationService
import dev.adminos.api.domain.notifications.ReminderEngine
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** The jobs registered for AOS-501: their names, schedules and the counts they report. */
class ScheduledJobsTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val prefs = InMemoryNotificationPreferencesRepository()
    private val notifications = InMemoryNotificationRepository()
    private val sender = notificationService(prefs, notifications)
    private val users = InMemoryUserRepository()

    private suspend fun user(active: Boolean = true, deleted: Boolean = false, createdDaysAgo: Long = 0) = users.save(
        User(email = "u-${UUID.randomUUID()}@example.com", googleId = "g-${UUID.randomUUID()}", isActive = active,
            deletedAt = if (deleted) Instant.now() else null,
            createdAt = Instant.now().minus(createdDaysAgo, ChronoUnit.DAYS).minus(1, ChronoUnit.HOURS))
    )

    @Test
    fun `bill reminders run daily at 09-00 in the configured zone and report counts`() = runBlocking<Unit> {
        val bills = InMemoryBillRepository()
        val calendar = dev.adminos.api.domain.identity.UserCalendar(users)
        val job = BillRemindersJob(ReminderEngine(bills, prefs, sender, calendar),
            dev.adminos.api.domain.notifications.RenewalReminders(InMemorySubscriptionRepository(), prefs, sender, calendar),
            dev.adminos.api.domain.financial.BillTrackingService(bills, InMemoryTransactionRepository()), ist)
        assertEquals(JobName("bill-reminders"), job.name)
        assertEquals(Schedule.Daily(LocalTime.of(9, 0), ist), job.schedule)
        assertEquals(java.time.Duration.ofMinutes(10), job.timeout, "default timeout")
        assertEquals(RetryPolicy.DEFAULT, job.retry, "default retry")
        assertEquals(mapOf("paidBySweep" to 0, "overdueMarked" to 0, "remindersSent" to 0, "renewalRemindersSent" to 0, "failed" to 0), job.run().counts)
    }

    @Test
    fun `onboarding nudges run daily at 11-00 and report counts`() = runBlocking<Unit> {
        user(createdDaysAgo = 1)
        val job = OnboardingNudgesJob(DropOffNudgeService(users, notifications, sender), ist)
        assertEquals(Schedule.Daily(LocalTime.of(11, 0), ist), job.schedule)
        assertEquals(mapOf("usersChecked" to 1, "nudgesSent" to 1, "failed" to 0), job.run().counts)
    }

    @Test
    fun `subscription detection runs nightly for active users only, and one user's failure doesn't stop the rest`() = runBlocking<Unit> {
        val withNetflix = user()
        val broken = user()
        user(active = false)
        user(deleted = true)
        val transactions = InMemoryTransactionRepository()
        listOf(65L, 35L, 5L).forEach { daysAgo ->
            transactions.save(Transaction(userId = withNetflix.id, type = TransactionType.DEBIT, amount = BigDecimal("649.00"),
                merchantName = "Netflix", paymentMethod = PaymentMethod.CARD_CREDIT, sourceType = "sms",
                transactedAt = Instant.now().minus(daysAgo, ChronoUnit.DAYS)))
        }
        val failingForOne = object : TransactionRepository by transactions {
            override suspend fun findDebitsByUserIdSince(userId: UUID, since: Instant): List<Transaction> =
                if (userId == broken.id) throw IOException("read timed out") else transactions.findDebitsByUserIdSince(userId, since)
        }
        val job = SubscriptionDetectionJob(users, SubscriptionDetectorService(failingForOne, InMemorySubscriptionRepository(), dev.adminos.api.domain.identity.UserCalendar(users)), ist)

        assertEquals(JobName("subscription-detection"), job.name)
        assertEquals(Schedule.Daily(LocalTime.of(4, 0), ist), job.schedule)
        assertEquals(mapOf("users" to 2, "subscriptionsFound" to 1, "failed" to 1), job.run().counts)
    }

    @Test
    fun `findActive skips inactive and deleted users`() = runBlocking<Unit> {
        val active = user()
        user(active = false)
        user(deleted = true)
        assertEquals(listOf(active.id), users.findActive().map { it.id })
    }
}
