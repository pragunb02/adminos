package dev.adminos.api.domain.coach.limits

import dev.adminos.api.domain.coach.settings.CoachSettingsService
import dev.adminos.api.domain.common.formatInr
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.domain.identity.UserRepository
import dev.adminos.api.domain.ingestion.pipeline.TransactionCreatedListener
import dev.adminos.api.domain.coach.lenses.LensMatcher
import dev.adminos.api.domain.notifications.NotificationService
import dev.adminos.api.domain.notifications.NotificationType
import dev.adminos.api.domain.scheduling.JobName
import dev.adminos.api.domain.scheduling.JobResult
import dev.adminos.api.domain.scheduling.Schedule
import dev.adminos.api.domain.scheduling.ScheduledJob
import dev.adminos.api.domain.scheduling.forEachIsolated
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** Where a coach push opens, and its data for the channels (Web Push maps `entity_type=coach` to /leaks). */
object CoachPush {
    const val ENTITY = "coach"
    fun data(kind: String, id: String) = mapOf("entity_type" to ENTITY, "entity_id" to id, "action" to kind)
}

/** The words of a limit push (deterministic, D-57/D-67): every number is a fact from the status. */
object LimitAlertWriter {
    fun title(s: LimitStatus, t: Threshold): String =
        if (t == Threshold.ALL) "${s.limit.label}: ${s.limit.period.word} limit reached"
        else "${s.limit.label}: ${t.percent}% of your ${s.limit.period.word} limit"

    fun body(s: LimitStatus): String {
        val period = if (s.limit.period == LimitPeriod.WEEKLY) "this week" else "this month"
        val tail = if (s.left.signum() >= 0) "${s.left.formatInr()} left" else "${s.left.negate().formatInr()} over"
        val days = when (s.daysLeft) { 0L -> "last day"; 1L -> "1 day to go"; else -> "${s.daysLeft} days to go" }
        return "${s.spent.formatInr()} of ${s.limit.amount.formatInr()} spent $period; $tail, $days."
    }
}

/**
 * Announces limit thresholds (AOS-904): for each limit, every threshold reached in the current period is
 * claimed in the [LimitAlertLedger]; if this call claimed any, one push goes out for the highest.
 * Claim-then-send: a crash between the two loses that push rather than repeating it (D-80: few nudges).
 * With alerts switched off, thresholds are still claimed silently, so switching on later doesn't
 * release a backlog.
 */
class LimitAlertService(
    private val limits: LimitService,
    private val ledger: LimitAlertLedger,
    private val settings: CoachSettingsService,
    private val notifications: NotificationService,
    private val calendar: UserCalendar,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = LoggerFactory.getLogger(LimitAlertService::class.java)

    /** @return how many pushes were sent */
    suspend fun check(userId: UUID): Int {
        val zone = calendar.zoneOf(userId)
        val statuses = limits.statuses(userId, zone)
        if (statuses.isEmpty()) return 0
        val on = settings.of(userId).limitAlerts
        var sent = 0
        for (s in statuses) {
            val fresh = s.reached.filter { ledger.claim(s.limit.id, s.period.from, it, clock.instant()) }
            val top = fresh.maxByOrNull { it.percent } ?: continue
            if (!on) continue
            notifications.send(
                userId = userId, type = NotificationType.COACH,
                title = LimitAlertWriter.title(s, top), body = LimitAlertWriter.body(s),
                data = CoachPush.data("limit", s.limit.id.toString()), userTimezone = zone.id
            )
            sent++
            logger.info("Limit {} reached {}% for user {}", s.limit.id, top.percent, userId)
        }
        return sent
    }
}

/** Right after a payment is stored: has a limit just been crossed? (AOS-904) */
class LimitAlertListener(private val alerts: LimitAlertService) : TransactionCreatedListener {
    override suspend fun onCreated(transaction: Transaction) {
        if (transaction.nature in LensMatcher.COUNTED) alerts.check(transaction.userId)
    }
}

/** Every 10 minutes: catches crossings the listener can't see (re-categorised payments, lens edits, new limits). */
class LimitAlertsJob(private val users: UserRepository, private val alerts: LimitAlertService) : ScheduledJob {
    private val logger = LoggerFactory.getLogger(LimitAlertsJob::class.java)
    override val name = JobName("coach-limit-alerts")
    override val schedule = Schedule.Every(Duration.ofMinutes(10))

    override suspend fun run(): JobResult {
        val active = users.findActive()
        var sent = 0
        val counts = forEachIsolated(active, logger, { "limit alerts for user ${it.id}" }) { sent += alerts.check(it.id) }
        return JobResult.of("users" to active.size, "alertsSent" to sent, "failed" to counts.failed)
    }
}
