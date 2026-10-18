package dev.adminos.api.domain.agent.briefing

import dev.adminos.api.domain.agent.AnomalyRepository
import dev.adminos.api.domain.agent.AnomalyStatus
import dev.adminos.api.domain.agent.Briefing
import dev.adminos.api.domain.agent.BriefingNarration
import dev.adminos.api.domain.agent.BriefingRepository
import dev.adminos.api.domain.agent.BriefingStatus
import dev.adminos.api.domain.agent.BriefingType
import dev.adminos.api.domain.agent.CategoryAmount
import dev.adminos.api.domain.analytics.AnalyticsService
import dev.adminos.api.domain.analytics.CategoryShare
import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.analytics.MerchantSpend
import dev.adminos.api.domain.common.formatInr
import dev.adminos.api.domain.common.toMoney
import dev.adminos.api.domain.financial.DueItem
import dev.adminos.api.domain.financial.DuesService
import dev.adminos.api.domain.financial.SubscriptionRepository
import dev.adminos.api.domain.financial.mandates.MandateService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.notifications.NotificationPreferences
import dev.adminos.api.domain.notifications.NotificationService
import dev.adminos.api.domain.notifications.NotificationType
import dev.adminos.api.domain.notifications.Weekday
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Clock
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Everything a weekly briefing says, gathered from the same sources as the console (AOS-506). */
data class BriefingFacts(
    val period: DateRange,
    val spent: BigDecimal,
    val received: BigDecimal,
    val previousSpent: BigDecimal,
    val topCategories: List<CategoryShare>,
    val topMerchant: MerchantSpend?,
    /** Unpaid bills, renewals and AutoPay debits in the 7 days from the briefing day. */
    val upcoming: List<DueItem>,
    val openAnomalies: Int,
    val flaggedSubscriptions: Int,
    val dormantMandates: List<String>
) {
    /** This week's spending minus the week before's. */
    val difference: BigDecimal get() = spent - previousSpent

    /** What the [upcoming] dues add up to. */
    val upcomingTotal: BigDecimal get() = upcoming.fold(BigDecimal.ZERO) { s, d -> s + d.amount }

    /** The first of the [upcoming] dues, or null when nothing is due. */
    val firstUpcoming: DueItem? get() = upcoming.minByOrNull { it.date }
}

/** Where the facts come from (one object, so the service keeps few dependencies). */
data class BriefingSources(
    val analytics: AnalyticsService,
    val dues: DuesService,
    val anomalies: AnomalyRepository,
    val subscriptions: SubscriptionRepository,
    val mandates: MandateService
)

/**
 * Weekly briefings (AOS-506): every number comes from the analytics the console shows; the text is
 * written by [BriefingWriter] from those numbers — deterministic, so it can't invent anything. An optional AI wording
 * of the same facts is added by [BriefingNarrator] when the user turned it on (AOS-716); the template always stays.
 *
 * **When:** on the user's briefing day and time (preferences), in their zone, about the 7 days
 * before that day. If the app was down at that moment it catches up later the same week; one
 * briefing per (user, week) — the database's unique key backs this up. A week that ended before
 * the user signed up gets none.
 */
class WeeklyBriefingService(
    private val sources: BriefingSources,
    private val briefings: BriefingRepository,
    private val notifications: NotificationService,
    private val narrator: BriefingNarrator,
    private val clock: Clock = Clock.systemUTC(),
    /** Parts other features add, e.g. the coach's report card (AOS-906); appended in order. */
    private val sections: List<BriefingSection> = emptyList()
) {
    private val logger = LoggerFactory.getLogger(WeeklyBriefingService::class.java)

    /** Makes and sends this week's briefing for [user] if it's due and not made yet. @return it, or null */
    suspend fun briefIfDue(user: User, prefs: NotificationPreferences): Briefing? {
        if (!prefs.weeklyBriefing) return null
        val zone = zoneOf(user)
        val now = clock.instant()
        val briefingDay = latestBriefingDay(now, zone, prefs.briefingDay.toDayOfWeek(), prefs.briefingTime)
        val period = DateRange(briefingDay.minusDays(7), briefingDay.minusDays(1))
        if (user.createdAt.atZone(zone).toLocalDate().isAfter(period.to)) return null
        if (briefings.findByUserAndPeriod(user.id, period.from, BriefingType.WEEKLY) != null) return null

        val started = System.nanoTime()
        val facts = gather(user, period, zone, briefingDay)
        val extras = sections.mapNotNull { it.render(user.id, period, zone) }
        val content = (listOf(BriefingWriter.write(facts)) + extras.flatMap { it.lines }).joinToString("\n")
        val briefing = briefings.save(Briefing(
            userId = user.id, periodStart = period.from, periodEnd = period.to, type = BriefingType.WEEKLY,
            content = content, contentStructured = structured(facts) + extras.associate { it.key to it.structured },
            totalSpent = facts.spent, totalIncome = facts.received,
            topCategories = facts.topCategories.map { CategoryAmount(it.category.name.lowercase(), it.spent) },
            subscriptionsFlagged = facts.flaggedSubscriptions, anomaliesDetected = facts.openAnomalies, billsUpcoming = facts.upcoming.size,
            status = BriefingStatus.GENERATED, modelUsed = MODEL, promptVersion = VERSION, tokensUsed = 0,
            generationMs = ((System.nanoTime() - started) / 1_000_000).toInt(), createdAt = now, updatedAt = now
        ))
        notifications.send(
            userId = user.id,
            type = NotificationType.BRIEFING,
            title = BriefingWriter.headline(facts),
            body = content.substringBefore("\n").take(300),
            data = mapOf("entity_type" to "briefing", "entity_id" to briefing.id.toString(), "action" to "view_briefing"),
            userTimezone = zone.id
        )
        logger.info("Weekly briefing {} made for user {} ({}..{})", briefing.id, user.id, period.from, period.to)
        return narrate(briefing, facts)
    }

    /**
     * The optional AI wording (AOS-716), asked **after** the template briefing is saved and sent: at most one model
     * call per briefing, and a failure afterwards (even storing the answer) never asks again.
     */
    private suspend fun narrate(briefing: Briefing, facts: BriefingFacts): Briefing {
        val narration = narrator.narrate(briefing.userId, facts)
        if (narration == BriefingNarration.None) return briefing
        val at = clock.instant()
        briefings.updateNarration(briefing.id, narration, at)
        return briefing.copy(narration = narration, updatedAt = at)
    }

    internal suspend fun gather(user: User, period: DateRange, zone: ZoneId, briefingDay: LocalDate): BriefingFacts {
        val summary = sources.analytics.summary(user.id, period, zone)
        val categories = sources.analytics.categories(user.id, period, zone).filter { it.spent.signum() > 0 }
        val merchant = sources.analytics.merchants(user.id, period, zone, limit = 1).firstOrNull()
        val until = briefingDay.plusDays(6)
        val months = listOf(YearMonth.from(briefingDay), YearMonth.from(until)).distinct()
        val upcoming = months.flatMap { sources.dues.month(user.id, it, briefingDay).items }
            .filter { it.status != "paid" && !it.date.isBefore(briefingDay) && !it.date.isAfter(until) }
            .distinctBy { Triple(it.kind, it.id, it.date) }
        return BriefingFacts(
            period = period,
            spent = summary.current.spent, received = summary.current.received, previousSpent = summary.previous.spent,
            topCategories = categories.take(3), topMerchant = merchant, upcoming = upcoming,
            openAnomalies = sources.anomalies.countByUserId(user.id, AnomalyStatus.OPEN).toInt(),
            flaggedSubscriptions = sources.subscriptions.countByUserId(user.id, flagged = true).toInt(),
            dormantMandates = sources.mandates.list(user.id, briefingDay).filter { it.dormant }.map { it.mandate.payee }
        )
    }

    /** Machine-readable copy of the facts (exact money strings), for the web and later formats. */
    private fun structured(f: BriefingFacts): Map<String, Any?> = mapOf(
        "spent" to f.spent.toMoney(), "received" to f.received.toMoney(), "previousSpent" to f.previousSpent.toMoney(),
        "topCategories" to f.topCategories.map { mapOf("category" to it.category.name.lowercase(), "spent" to it.spent.toMoney(), "count" to it.count) },
        "topMerchant" to f.topMerchant?.let { mapOf("merchant" to it.merchant, "spent" to it.spent.toMoney(), "count" to it.count) },
        "upcoming" to f.upcoming.map { mapOf("kind" to it.kind.name.lowercase(), "name" to it.name, "amount" to it.amount.toMoney(), "date" to it.date.toString()) },
        "openAnomalies" to f.openAnomalies, "flaggedSubscriptions" to f.flaggedSubscriptions, "dormantMandates" to f.dormantMandates
    )

    private fun zoneOf(user: User): ZoneId = try { ZoneId.of(user.timezone) } catch (e: DateTimeException) { DEFAULT_ZONE }

    companion object {
        val DEFAULT_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
        const val MODEL = "deterministic"
        /** v2: sections (the coach's report card, AOS-906) follow the v1 lines. */
        const val VERSION = "weekly-v2"

        /** The most recent briefing day whose briefing time has passed at [now]. */
        fun latestBriefingDay(now: Instant, zone: ZoneId, day: DayOfWeek, time: LocalTime): LocalDate {
            val local = now.atZone(zone)
            val candidate = local.toLocalDate().with(TemporalAdjusters.previousOrSame(day))
            val moment = candidate.atTime(time).atZone(zone).toInstant()
            return if (moment.isAfter(now)) candidate.minusWeeks(1) else candidate
        }

        private fun Weekday.toDayOfWeek(): DayOfWeek = DayOfWeek.entries[ordinal] // MON..SUN in both
    }
}

/**
 * Writes the briefing in plain words from [BriefingFacts] (AOS-506). Deterministic: the same facts
 * always give the same text, and every number in it is one of the facts.
 */
object BriefingWriter {
    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val WEEKDAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    fun headline(f: BriefingFacts): String =
        if (f.spent.signum() == 0) "Your week: no spending recorded" else "Your week: ${f.spent.formatInr()} spent"

    fun write(f: BriefingFacts): String = buildList {
        add(spendLine(f))
        if (f.topCategories.isNotEmpty()) add("Most went to " + f.topCategories.joinToString(", ") { "${label(it)} (${it.spent.formatInr()})" } + ".")
        f.topMerchant?.let { add("Biggest payee: ${it.merchant} (${it.spent.formatInr()} over ${it.count} payment${if (it.count == 1) "" else "s"}).") }
        if (f.received.signum() > 0) add("You received ${f.received.formatInr()}.")
        f.firstUpcoming?.let { first ->
            add("Coming up in the next 7 days: ${f.upcoming.size} due${if (f.upcoming.size == 1) "" else "s"} totalling ${f.upcomingTotal.formatInr()} — first ${first.name} on ${first.date.format(WEEKDAY)}.")
        }
        if (f.openAnomalies > 0) add("${f.openAnomalies} unusual charge${if (f.openAnomalies == 1) "" else "s"} waiting for your review.")
        if (f.flaggedSubscriptions > 0) add("${f.flaggedSubscriptions} subscription${if (f.flaggedSubscriptions == 1) " looks" else "s look"} worth a second look.")
        if (f.dormantMandates.isNotEmpty()) add("Unused AutoPay: ${f.dormantMandates.joinToString(", ")} — revoke in your UPI app or bank if no longer needed.")
    }.joinToString("\n")

    private fun spendLine(f: BriefingFacts): String {
        val range = "${f.period.from.format(DAY)}–${f.period.to.format(DAY)}"
        if (f.spent.signum() == 0) return "A quiet week ($range): no spending recorded."
        val diff = f.difference
        val compare = when {
            f.previousSpent.signum() == 0 -> "nothing the week before"
            diff.signum() > 0 -> "${diff.formatInr()} more than the week before"
            diff.signum() < 0 -> "${diff.abs().formatInr()} less than the week before"
            else -> "the same as the week before"
        }
        return "Last week ($range) you spent ${f.spent.formatInr()} — $compare."
    }

    /** A category as the briefing names it ("Food"). */
    internal fun label(c: CategoryShare) = c.category.name.lowercase().replaceFirstChar { it.uppercase() }
}
