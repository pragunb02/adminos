package dev.adminos.api.domain.coach.digest

import dev.adminos.api.domain.analytics.AnalyticsService
import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.coach.leaks.LeakService
import dev.adminos.api.domain.coach.leaks.LensLeak
import dev.adminos.api.domain.coach.limits.CoachPush
import dev.adminos.api.domain.coach.limits.LimitPeriod
import dev.adminos.api.domain.coach.limits.LimitService
import dev.adminos.api.domain.coach.limits.LimitStatus
import dev.adminos.api.domain.coach.settings.CoachSettingsService
import dev.adminos.api.domain.common.formatInr
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.domain.identity.UserRepository
import dev.adminos.api.domain.notifications.NotificationService
import dev.adminos.api.domain.notifications.NotificationType
import dev.adminos.api.domain.scheduling.JobName
import dev.adminos.api.domain.scheduling.JobResult
import dev.adminos.api.domain.scheduling.Schedule
import dev.adminos.api.domain.scheduling.ScheduledJob
import dev.adminos.api.domain.scheduling.forEachIsolated
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class DigestKind { EVENING }

/** Which digests were sent (AOS-905): one per (user, kind, local day); [claim] is the only way to send. */
interface DigestLedger {
    /** @return true if this call recorded it (go ahead and send) */
    suspend fun claim(userId: UUID, kind: DigestKind, day: LocalDate, at: Instant): Boolean
}

class InMemoryDigestLedger : DigestLedger {
    private val sent = ConcurrentHashMap.newKeySet<Triple<UUID, DigestKind, LocalDate>>()
    override suspend fun claim(userId: UUID, kind: DigestKind, day: LocalDate, at: Instant) = sent.add(Triple(userId, kind, day))
}

/** Everything an evening digest says; every number comes from analytics, leaks or limits. */
data class DigestFacts(
    val day: LocalDate,
    /** Spend − refunds today, the console's number (D-45/D-46). */
    val spent: BigDecimal,
    val payments: Int,
    /** Up to two lenses with spending today, largest first. */
    val topLeaks: List<LensLeak>,
    /** Up to two limits, the most used first. */
    val limits: List<LimitStatus>
) {
    val quiet: Boolean get() = spent.signum() == 0 && payments == 0
}

/** The digest's words (deterministic, D-57): the same facts always give the same text. */
object DigestWriter {
    fun title(f: DigestFacts): String = if (f.quiet) "Today: nothing spent" else "Today: ${f.spent.formatInr()} spent"

    fun body(f: DigestFacts): String = buildList {
        if (f.quiet) add("No spending recorded today.")
        else if (f.topLeaks.isEmpty()) add("${plural(f.payments, "payment")}, none on your habits.")
        else add("Leaks: " + f.topLeaks.joinToString(", ") { leak(it) } + ".")
        if (f.limits.isNotEmpty()) add("Limits: " + f.limits.joinToString(", ") { limit(it) } + ".")
    }.joinToString(" ")

    private fun leak(l: LensLeak): String {
        val n = l.numbers
        val late = if (n.lateNight.orders > 0) ", ${n.lateNight.orders} late" else ""
        return "${l.lens.label} ${n.spent.formatInr()} (${plural(n.orders, "order")}$late)"
    }

    private fun limit(s: LimitStatus): String {
        val pct = s.used.multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).toPlainString()
        val period = if (s.limit.period == LimitPeriod.WEEKLY) "this week" else "this month"
        return "${s.limit.label} $pct% of ${s.limit.amount.formatInr()} $period"
    }

    private fun plural(n: Int, one: String) = "$n ${if (n == 1) one else "${one}s"}"
}

/** Where the facts come from (one object keeps the service's dependencies few). */
data class DigestSources(val analytics: AnalyticsService, val leaks: LeakService, val limits: LimitService)

/**
 * The evening digest (AOS-905, D-80): once a day at the owner's chosen time (default 21:30, their zone),
 * until local midnight; a missed evening isn't sent the next day. Claim (ledger) then send: at most once.
 * With "skip on a zero day", a day with no spending sends nothing and claims nothing, so a later payment
 * that evening still gets its digest.
 */
class EveningDigestService(
    private val sources: DigestSources,
    private val settings: CoachSettingsService,
    private val ledger: DigestLedger,
    private val notifications: NotificationService,
    private val calendar: UserCalendar,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = LoggerFactory.getLogger(EveningDigestService::class.java)

    /** @return true if a digest was sent now */
    suspend fun digestIfDue(user: User): Boolean {
        val prefs = settings.of(user.id)
        if (!prefs.eveningDigest) return false
        val zone = calendar.zoneOf(user.id)
        val now = ZonedDateTime.now(clock.withZone(zone))
        val today = now.toLocalDate()
        if (now.toLocalTime() < prefs.digestTime) return false
        val facts = gather(user.id, today)
        if (facts.quiet && prefs.digestSkipZeroDay) return false
        if (!ledger.claim(user.id, DigestKind.EVENING, today, clock.instant())) return false
        notifications.send(
            userId = user.id, type = NotificationType.COACH,
            title = DigestWriter.title(facts), body = DigestWriter.body(facts),
            data = CoachPush.data("digest", today.toString()), userTimezone = zone.id
        )
        logger.info("Evening digest sent for user {} ({})", user.id, today)
        return true
    }

    internal suspend fun gather(userId: UUID, today: LocalDate): DigestFacts {
        val zone = calendar.zoneOf(userId)
        val day = DateRange(today, today)
        val summary = sources.analytics.summary(userId, day, zone)
        val leaks = sources.leaks.report(userId, day, zone).lenses.filter { it.numbers.spent.signum() > 0 }.take(2)
        val limits = sources.limits.statuses(userId, zone).sortedByDescending { it.used }.take(2)
        return DigestFacts(today, summary.current.spent, summary.spendCount, leaks, limits)
    }
}

/** Every 15 minutes: sends the digests that are due (each user has their own time and zone). */
class EveningDigestJob(private val users: UserRepository, private val digests: EveningDigestService) : ScheduledJob {
    private val logger = LoggerFactory.getLogger(EveningDigestJob::class.java)
    override val name = JobName("coach-evening-digest")
    override val schedule = Schedule.Every(Duration.ofMinutes(15))

    override suspend fun run(): JobResult {
        val active = users.findActive()
        var sent = 0
        val counts = forEachIsolated(active, logger, { "evening digest for user ${it.id}" }) { if (digests.digestIfDue(it)) sent++ }
        return JobResult.of("users" to active.size, "digestsSent" to sent, "failed" to counts.failed)
    }
}
