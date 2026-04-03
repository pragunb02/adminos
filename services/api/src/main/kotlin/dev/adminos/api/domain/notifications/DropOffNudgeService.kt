package dev.adminos.api.domain.notifications

import dev.adminos.api.domain.identity.OnboardingStatus
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserRepository
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Drop-off recovery nudge service.
 * Runs daily, finds users with incomplete onboarding, sends nudges.
 *
 * Fix #3: getSourceNudgeCount now filters by source via notification data
 * Fix #4: runNudges now queries users itself via UserRepository
 */
class DropOffNudgeService(
    private val userRepository: UserRepository,
    private val notificationRepository: NotificationRepository,
    private val notificationService: NotificationService
) {
    private val logger = LoggerFactory.getLogger(DropOffNudgeService::class.java)

    companion object {
        const val MAX_NUDGES_PER_SOURCE = 3
        val GMAIL_NUDGE_DAYS = listOf(1, 3, 7)
        val SMS_NUDGE_DAYS = listOf(3, 5, 7)
    }

    /**
     * Run the daily nudge check.
     * Fix #4: Queries incomplete users from repository instead of taking as parameter.
     */
    suspend fun runNudges() {
        val now = Instant.now()
        val incompleteUsers = userRepository.findByOnboardingIncomplete()

        logger.info("Running drop-off nudges: {} incomplete users found", incompleteUsers.size)

        for (user in incompleteUsers) {
            try {
                processUserNudges(user, now)
            } catch (e: Exception) {
                logger.error("Failed to process nudges for user {}: {}", user.id, e.message)
            }
        }
    }

    internal suspend fun processUserNudges(user: User, now: Instant) {
        val daysSinceCreation = ChronoUnit.DAYS.between(user.createdAt, now).toInt()
        if (daysSinceCreation < 1) return

        // Fix #3: Get source-specific nudge counts by filtering notification data
        val allNudges = notificationRepository.findByUserAndType(user.id, NotificationType.NUDGE)

        if (needsGmailNudge(user.onboardingStatus)) {
            val gmailNudgesSent = allNudges.count { it.data?.get("source") == "gmail" }
            if (gmailNudgesSent < MAX_NUDGES_PER_SOURCE && daysSinceCreation in GMAIL_NUDGE_DAYS) {
                sendGmailNudge(user, daysSinceCreation)
            }
        }

        if (needsSmsNudge(user.onboardingStatus)) {
            val smsNudgesSent = allNudges.count { it.data?.get("source") == "sms" }
            if (smsNudgesSent < MAX_NUDGES_PER_SOURCE && daysSinceCreation in SMS_NUDGE_DAYS) {
                sendSmsNudge(user, daysSinceCreation)
            }
        }
    }

    private suspend fun sendGmailNudge(user: User, daysSinceCreation: Int) {
        notificationService.send(
            userId = user.id,
            type = NotificationType.NUDGE,
            title = "Connect your Gmail to discover bills",
            body = "AdminOS can automatically find your bills and subscriptions from email.",
            data = mapOf("source" to "gmail", "nudge_day" to daysSinceCreation.toString()),
            userTimezone = user.timezone
        )
        logger.info("Sent Gmail nudge to user {} (day {})", user.id, daysSinceCreation)
    }

    private suspend fun sendSmsNudge(user: User, daysSinceCreation: Int) {
        notificationService.send(
            userId = user.id,
            type = NotificationType.NUDGE,
            title = "Grant SMS access to track transactions",
            body = "AdminOS can parse your bank SMS to track spending automatically.",
            data = mapOf("source" to "sms", "nudge_day" to daysSinceCreation.toString()),
            userTimezone = user.timezone
        )
        logger.info("Sent SMS nudge to user {} (day {})", user.id, daysSinceCreation)
    }

    internal fun needsGmailNudge(status: OnboardingStatus): Boolean =
        status == OnboardingStatus.STARTED

    internal fun needsSmsNudge(status: OnboardingStatus): Boolean =
        status in listOf(OnboardingStatus.STARTED, OnboardingStatus.GMAIL_CONNECTED)

    fun shouldSendNudge(daysSinceCreation: Int, nudgeDays: List<Int>, nudgesSent: Int): Boolean {
        if (nudgesSent >= MAX_NUDGES_PER_SOURCE) return false
        return daysSinceCreation in nudgeDays
    }
}
