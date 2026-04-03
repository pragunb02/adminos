package dev.adminos.api.domain.notifications

import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.OnboardingStatus
import dev.adminos.api.domain.identity.User
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DropOffNudgeServiceTest {

    private lateinit var userRepo: InMemoryUserRepository
    private lateinit var prefsRepo: InMemoryNotificationPreferencesRepository
    private lateinit var notifRepo: InMemoryNotificationRepository
    private lateinit var deviceRepo: InMemoryDeviceRepository
    private lateinit var pushService: PushService
    private lateinit var notificationService: NotificationService
    private lateinit var nudgeService: DropOffNudgeService

    @BeforeEach
    fun setup() {
        userRepo = InMemoryUserRepository()
        prefsRepo = InMemoryNotificationPreferencesRepository()
        notifRepo = InMemoryNotificationRepository()
        deviceRepo = InMemoryDeviceRepository()
        pushService = PushService(deviceRepo)
        notificationService = NotificationService(prefsRepo, notifRepo, pushService)
        nudgeService = DropOffNudgeService(userRepo, notifRepo, notificationService)
    }

    // ── Max Nudge Count Enforcement ──

    @Test
    fun `shouldSendNudge returns true when under max and on nudge day`() {
        val result = nudgeService.shouldSendNudge(
            daysSinceCreation = 3,
            nudgeDays = listOf(1, 3, 7),
            nudgesSent = 0
        )
        assertTrue(result)
    }

    @Test
    fun `shouldSendNudge returns false when at max nudges`() {
        val result = nudgeService.shouldSendNudge(
            daysSinceCreation = 7,
            nudgeDays = listOf(1, 3, 7),
            nudgesSent = 3
        )
        assertFalse(result)
    }

    @Test
    fun `shouldSendNudge returns false when over max nudges`() {
        val result = nudgeService.shouldSendNudge(
            daysSinceCreation = 7,
            nudgeDays = listOf(1, 3, 7),
            nudgesSent = 5
        )
        assertFalse(result)
    }

    @Test
    fun `shouldSendNudge returns false when not on nudge day`() {
        val result = nudgeService.shouldSendNudge(
            daysSinceCreation = 2,
            nudgeDays = listOf(1, 3, 7),
            nudgesSent = 0
        )
        assertFalse(result)
    }

    @Test
    fun `shouldSendNudge respects max nudges per source`() {
        val result = nudgeService.shouldSendNudge(
            daysSinceCreation = 3,
            nudgeDays = listOf(1, 3, 7),
            nudgesSent = 3 // at max — should not send
        )
        assertFalse(result)
    }

    // ── Onboarding Status Checks ──

    @Test
    fun `needsGmailNudge true for STARTED status`() {
        assertTrue(nudgeService.needsGmailNudge(OnboardingStatus.STARTED))
    }

    @Test
    fun `needsGmailNudge false for GMAIL_CONNECTED status`() {
        assertFalse(nudgeService.needsGmailNudge(OnboardingStatus.GMAIL_CONNECTED))
    }

    @Test
    fun `needsSmsNudge true for STARTED status`() {
        assertTrue(nudgeService.needsSmsNudge(OnboardingStatus.STARTED))
    }

    @Test
    fun `needsSmsNudge true for GMAIL_CONNECTED status`() {
        assertTrue(nudgeService.needsSmsNudge(OnboardingStatus.GMAIL_CONNECTED))
    }

    @Test
    fun `needsSmsNudge false for COMPLETED status`() {
        assertFalse(nudgeService.needsSmsNudge(OnboardingStatus.COMPLETED))
    }

    // ── Integration: Nudge Sending ──

    @Test
    fun `processUserNudges sends gmail nudge on day 1`() = runBlocking {
        val user = User(
            email = "test@example.com",
            googleId = "g123",
            onboardingStatus = OnboardingStatus.STARTED,
            createdAt = Instant.now().minus(1, ChronoUnit.DAYS)
        )
        userRepo.save(user)

        // Set up prefs with no quiet hours interference
        val prefs = NotificationPreferences(
            userId = user.id,
            quietHoursStart = LocalTime.of(2, 0),
            quietHoursEnd = LocalTime.of(3, 0)
        )
        prefsRepo.save(prefs)

        nudgeService.processUserNudges(user, Instant.now())

        val notifications = notifRepo.findByUserId(user.id)
        assertTrue(notifications.isNotEmpty())
        assertTrue(notifications.any { it.type == NotificationType.NUDGE })
    }

    @Test
    fun `processUserNudges does not send for completed users`() = runBlocking {
        val user = User(
            email = "done@example.com",
            googleId = "g456",
            onboardingStatus = OnboardingStatus.COMPLETED,
            createdAt = Instant.now().minus(3, ChronoUnit.DAYS)
        )
        userRepo.save(user)

        nudgeService.processUserNudges(user, Instant.now())

        val notifications = notifRepo.findByUserId(user.id)
        assertTrue(notifications.isEmpty())
    }
}
