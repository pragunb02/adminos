package dev.adminos.api.domain.coach

import dev.adminos.api.domain.coach.leaks.LeakService
import dev.adminos.api.domain.coach.lenses.InMemoryLensOverrideRepository
import dev.adminos.api.domain.coach.lenses.LensMatcher
import dev.adminos.api.domain.coach.lenses.LensService
import dev.adminos.api.domain.coach.limits.*
import dev.adminos.api.domain.coach.settings.CoachSettingsService
import dev.adminos.api.domain.coach.settings.InMemoryCoachSettingsRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.domain.notifications.InMemoryNotificationPreferencesRepository
import dev.adminos.api.domain.notifications.InMemoryNotificationRepository
import dev.adminos.api.domain.notifications.Notification
import dev.adminos.api.domain.notifications.NotificationService
import dev.adminos.api.domain.notifications.NotificationType
import dev.adminos.api.domain.notifications.delivery.InAppChannel
import dev.adminos.api.domain.notifications.delivery.NotificationDispatcher
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** A clock tests can move; unlike a fixed clock it keeps the zone it is asked for. */
class MovableClock(var now: Instant, private val zone: ZoneId = ZoneId.of("UTC")) : Clock() {
    fun at(local: LocalDateTime, zone: ZoneId = CoachFixture.IST) { now = local.atZone(zone).toInstant() }
    fun advance(by: Duration) { now = now.plus(by) }
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock {
        val base = this
        return object : Clock() {
            override fun instant(): Instant = base.now
            override fun getZone(): ZoneId = zone
            override fun withZone(zone: ZoneId): Clock = base.withZone(zone)
        }
    }
}

/** The whole coach on in-memory parts, for service tests (no Koin, no database). */
class CoachKit(val clock: MovableClock = MovableClock(LocalDateTime.of(2026, 11, 18, 12, 0).atZone(CoachFixture.IST).toInstant())) {
    val users = InMemoryUserRepository()
    val owner: User = User(id = CoachFixture.USER, email = "owner@example.com", googleId = "g-owner", role = UserRole.OWNER, timezone = "Asia/Kolkata",
        createdAt = Instant.parse("2026-01-01T00:00:00Z"))
    val transactions = InMemoryTransactionRepository()
    val lenses = LensService(LensMatcher(), InMemoryLensOverrideRepository(), clock)
    val leaks = LeakService(transactions, lenses, clock)
    val limitRepo = InMemorySpendingLimitRepository()
    val ledger = InMemoryLimitAlertLedger()
    val dismissals = InMemorySuggestionDismissals()
    val settings = CoachSettingsService(InMemoryCoachSettingsRepository(), clock)
    val limits = LimitService(limitRepo, ledger, dismissals, leaks, RepositorySpendHistory(transactions), clock)
    val notificationStore = InMemoryNotificationRepository()
    val notifications = NotificationService(InMemoryNotificationPreferencesRepository(), notificationStore,
        NotificationDispatcher(listOf(InAppChannel()), users, notificationStore, clock), clock)
    val calendar = UserCalendar(users, clock)
    val alerts = LimitAlertService(limits, ledger, settings, notifications, calendar, clock)
    val analytics = dev.adminos.api.domain.analytics.AnalyticsService(dev.adminos.api.domain.analytics.InMemoryAnalyticsRepository(transactions), clock)
    val goals = dev.adminos.api.domain.coach.goals.GoalService(dev.adminos.api.domain.coach.goals.InMemoryGoalRepository(), leaks, clock)
    val digestLedger = dev.adminos.api.domain.coach.digest.InMemoryDigestLedger()
    val digests = dev.adminos.api.domain.coach.digest.EveningDigestService(
        dev.adminos.api.domain.coach.digest.DigestSources(analytics, leaks, limits), settings, digestLedger, notifications, calendar, clock)

    init {
        runBlocking { users.save(owner) }
    }

    /** Inside [MovableClock.instant]'s current reading, a local time in India. */
    fun now(local: LocalDateTime) = clock.at(local)

    suspend fun pushes(type: NotificationType = NotificationType.COACH): List<Notification> =
        notificationStore.findByUserAndType(owner.id, type).sortedBy { it.createdAt }

    /** Transactions on many days so the learning month is over (one small unrelated spend per day back to [from]). */
    suspend fun history(from: LocalDateTime) {
        transactions.save(CoachFixture.txn("Corner Chemist", "10.00", from, user = owner.id))
    }

    val user: UUID get() = owner.id
}
