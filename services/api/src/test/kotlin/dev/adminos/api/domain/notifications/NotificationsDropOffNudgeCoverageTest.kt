package dev.adminos.api.domain.notifications

import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.OnboardingStatus
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.notifications.NotificationsFixtures.prefs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Disabled
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DropOffNudgeService (AOS-312): which source is nudged on which day, caps, and error isolation. */
class NotificationsDropOffNudgeCoverageTest {

    private val users = InMemoryUserRepository()
    private val prefsRepo = InMemoryNotificationPreferencesRepository()
    private val notifRepo = InMemoryNotificationRepository()
    private val service = DropOffNudgeService(users, notifRepo, NotificationService(prefsRepo, notifRepo, PushService(InMemoryDeviceRepository())))
    private val now = Instant.parse("2026-10-16T06:30:00Z")

    private suspend fun user(daysAgo: Long, status: OnboardingStatus = OnboardingStatus.STARTED, createdAt: Instant = now.minus(daysAgo, ChronoUnit.DAYS)): User {
        val u = User(email = "pragun+${UUID.randomUUID()}@gmail.com", googleId = "g-${UUID.randomUUID()}", onboardingStatus = status, createdAt = createdAt)
        prefsRepo.save(prefs(u.id))
        return users.save(u)
    }

    private suspend fun sources(u: User) =
        notifRepo.findByUserAndType(u.id, NotificationType.NUDGE).map { it.data!!["source"]!! }.sorted()

    private fun priorNudge(u: User, source: String?) =
        Notification(userId = u.id, type = NotificationType.NUDGE, title = "n", body = "b", channel = NotificationChannel.IN_APP,
                     data = source?.let { mapOf("source" to it) })

    @Test
    fun `the nudge schedule is days 1-3-7 for Gmail and 3-5-7 for SMS, at most 3 each`() {
        assertEquals(listOf(1, 3, 7), DropOffNudgeService.GMAIL_NUDGE_DAYS)
        assertEquals(listOf(3, 5, 7), DropOffNudgeService.SMS_NUDGE_DAYS)
        assertEquals(3, DropOffNudgeService.MAX_NUDGES_PER_SOURCE)
    }

    @Test
    fun `a user created less than a day ago is not nudged`() = runBlocking<Unit> {
        val u = user(0)

        service.processUserNudges(u, now)

        assertTrue(notifRepo.findByUserId(u.id).isEmpty())
    }

    @Test
    fun `a new user on day 1 gets only the Gmail nudge`() = runBlocking<Unit> {
        val u = user(1)

        service.processUserNudges(u, now)

        val n = notifRepo.findByUserAndType(u.id, NotificationType.NUDGE).single()
        assertEquals(mapOf("source" to "gmail", "nudge_day" to "1"), n.data)
        assertEquals("Connect your Gmail to discover bills", n.title)
    }

    @Test
    fun `a new user on day 3 gets both Gmail and SMS nudges`() = runBlocking<Unit> {
        val u = user(3)

        service.processUserNudges(u, now)

        assertEquals(listOf("gmail", "sms"), sources(u))
    }

    @Test
    fun `a Gmail-connected user on day 5 gets only the SMS nudge`() = runBlocking<Unit> {
        val u = user(5, OnboardingStatus.GMAIL_CONNECTED)

        service.processUserNudges(u, now)

        val n = notifRepo.findByUserAndType(u.id, NotificationType.NUDGE).single()
        assertEquals("Grant SMS access to track transactions", n.title)
        assertEquals("5", n.data!!["nudge_day"])
    }

    @Test
    fun `a day that is not a nudge day sends nothing`() = runBlocking<Unit> {
        val u = user(2)

        service.processUserNudges(u, now)

        assertTrue(notifRepo.findByUserId(u.id).isEmpty())
    }

    @Test
    fun `a user past SMS grant is never nudged`() = runBlocking<Unit> {
        val u = user(7, OnboardingStatus.SMS_GRANTED)

        service.processUserNudges(u, now)

        assertTrue(notifRepo.findByUserId(u.id).isEmpty())
    }

    @Test
    fun `three earlier Gmail nudges cap further Gmail nudges but not SMS`() = runBlocking<Unit> {
        val u = user(7)
        repeat(3) { notifRepo.save(priorNudge(u, "gmail")) }

        service.processUserNudges(u, now)

        assertEquals(listOf("gmail", "gmail", "gmail", "sms"), sources(u))
    }

    @Test
    fun `three earlier SMS nudges cap further SMS nudges`() = runBlocking<Unit> {
        val u = user(7, OnboardingStatus.GMAIL_CONNECTED)
        repeat(3) { notifRepo.save(priorNudge(u, "sms")) }

        service.processUserNudges(u, now)

        assertEquals(3, notifRepo.findByUserAndType(u.id, NotificationType.NUDGE).size)
    }

    @Test
    fun `nudges without a source do not count toward a source cap`() = runBlocking<Unit> {
        val u = user(1)
        repeat(3) { notifRepo.save(priorNudge(u, null)) }

        service.processUserNudges(u, now)

        assertEquals(4, notifRepo.findByUserAndType(u.id, NotificationType.NUDGE).size)
    }

    @Test
    fun `runNudges nudges incomplete users and skips completed ones`() = runBlocking<Unit> {
        val pending = user(0, createdAt = Instant.now().minus(1, ChronoUnit.DAYS).minus(1, ChronoUnit.HOURS))
        val done = user(0, OnboardingStatus.COMPLETED, createdAt = Instant.now().minus(1, ChronoUnit.DAYS).minus(1, ChronoUnit.HOURS))

        service.runNudges()

        assertEquals(listOf("gmail"), sources(pending))
        assertTrue(notifRepo.findByUserId(done.id).isEmpty())
    }

    @Test
    fun `runNudges keeps going when one user fails`() = runBlocking<Unit> {
        val created = Instant.now().minus(1, ChronoUnit.DAYS).minus(1, ChronoUnit.HOURS)
        val broken = user(0, createdAt = created)
        val ok = user(0, createdAt = created)
        val failingRepo = FailingForUserNotificationRepository(broken.id)
        val svc = DropOffNudgeService(users, failingRepo, NotificationService(prefsRepo, failingRepo, PushService(InMemoryDeviceRepository())))

        svc.runNudges()

        assertEquals(1, failingRepo.findByUserAndType(ok.id, NotificationType.NUDGE).size)
    }

    @Test
    fun `with default channels the day-3 Gmail nudge is still sent after the day-1 nudge`() = runBlocking<Unit> {
        val u = user(1)
        prefsRepo.update(prefsRepo.findByUserId(u.id)!!.copy(pushNotifications = true, emailNotifications = true))
        service.processUserNudges(u, now)

        service.processUserNudges(u, now.plus(2, ChronoUnit.DAYS))

        val days = notifRepo.findByUserAndType(u.id, NotificationType.NUDGE)
            .filter { it.data!!["source"] == "gmail" }.map { it.data!!["nudge_day"] }.toSet()
        assertEquals(setOf("1", "3"), days)
    }

    @Test
    fun `running twice on the same day sends that day's nudge once (AOS-613)`() = runBlocking<Unit> {
        val u = user(1)

        service.processUserNudges(u, now)
        service.processUserNudges(u, now.plus(6, ChronoUnit.HOURS))

        val days = notifRepo.findByUserAndType(u.id, NotificationType.NUDGE).map { it.data!!["nudge_day"] }.toSet()
        assertEquals(setOf("1"), days)
        val sends = notifRepo.findByUserAndType(u.id, NotificationType.NUDGE).groupBy { it.channel }.values.maxOf { it.size }
        assertEquals(1, sends, "one send per channel")
    }
}
