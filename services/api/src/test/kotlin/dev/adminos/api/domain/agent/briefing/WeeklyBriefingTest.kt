package dev.adminos.api.domain.agent.briefing

import dev.adminos.api.domain.agent.Anomaly
import dev.adminos.api.domain.agent.AnomalyType
import dev.adminos.api.domain.agent.BriefingType
import dev.adminos.api.domain.agent.InMemoryAnomalyRepository
import dev.adminos.api.domain.agent.InMemoryBriefingRepository
import dev.adminos.api.domain.analytics.AnalyticsService
import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.analytics.InMemoryAnalyticsRepository
import dev.adminos.api.domain.common.formatInr
import dev.adminos.api.domain.financial.*
import dev.adminos.api.domain.financial.mandates.InMemoryMandateRepository
import dev.adminos.api.domain.financial.mandates.MandateService
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.notifications.InMemoryNotificationPreferencesRepository
import dev.adminos.api.domain.notifications.InMemoryNotificationRepository
import dev.adminos.api.domain.notifications.NotificationPreferences
import dev.adminos.api.domain.notifications.NotificationType
import dev.adminos.api.domain.notifications.Weekday
import dev.adminos.api.domain.notifications.notificationService
import dev.adminos.api.domain.scheduling.TestClock
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Weekly briefings from real data (AOS-506): when, what it says, once per week. */
class WeeklyBriefingTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    /** Mon 12 Oct 2026, 09:00 IST: the default briefing (Monday 08:00) is due, about 5–11 Oct. */
    private val clock = TestClock(Instant.parse("2026-10-12T03:30:00Z"))
    private val users = InMemoryUserRepository()
    private val transactions = InMemoryTransactionRepository()
    private val bills = InMemoryBillRepository()
    private val subscriptions = InMemorySubscriptionRepository()
    private val anomalies = InMemoryAnomalyRepository()
    private val mandates = MandateService(InMemoryMandateRepository(), transactions)
    private val briefings = InMemoryBriefingRepository()
    private val prefsRepo = InMemoryNotificationPreferencesRepository()
    private val notifications = InMemoryNotificationRepository()
    private val service = WeeklyBriefingService(
        BriefingSources(AnalyticsService(InMemoryAnalyticsRepository(transactions), clock), DuesService(bills, subscriptions, mandates), anomalies, subscriptions, mandates),
        briefings, notificationService(prefsRepo, notifications, clock = clock), BriefingNarrator(NarrationFixture.gatewayWithNoRoutes(clock)), clock
    )
    private val user = runBlocking { users.save(User(email = "p@example.com", googleId = "g", createdAt = Instant.parse("2026-01-01T00:00:00Z"))) }
    private val prefs = NotificationPreferences(userId = user.id)

    private suspend fun spend(amount: String, merchant: String, category: TransactionCategory, day: Int, month: Int = 10) =
        transactions.save(Transaction(userId = user.id, type = TransactionType.DEBIT, amount = BigDecimal(amount), merchantName = merchant,
            category = category, transactedAt = LocalDate.of(2026, month, day).atTime(13, 0).atZone(ist).toInstant()))

    @Test
    fun `the briefing day is the latest one whose time has passed, in the user's zone`() {
        val mon8 = LocalTime.of(8, 0)
        assertEquals(LocalDate.of(2026, 10, 12), WeeklyBriefingService.latestBriefingDay(Instant.parse("2026-10-12T03:30:00Z"), ist, DayOfWeek.MONDAY, mon8))
        assertEquals(LocalDate.of(2026, 10, 5), WeeklyBriefingService.latestBriefingDay(Instant.parse("2026-10-12T02:00:00Z"), ist, DayOfWeek.MONDAY, mon8), "07:30 IST: not yet")
        assertEquals(LocalDate.of(2026, 10, 9), WeeklyBriefingService.latestBriefingDay(Instant.parse("2026-10-12T03:30:00Z"), ist, DayOfWeek.FRIDAY, mon8))
    }

    @Test
    fun `a briefing tells the week in numbers that are all facts, and is sent once`() = runBlocking<Unit> {
        spend("1200.00", "Swiggy", TransactionCategory.FOOD, 6)
        spend("800.00", "Swiggy", TransactionCategory.FOOD, 8)
        spend("1500.50", "Amazon", TransactionCategory.SHOPPING, 9)
        spend("600.00", "Uber", TransactionCategory.TRANSPORT, 10)
        spend("2000.00", "Last week", TransactionCategory.FOOD, 1) // the week before: 2,000
        transactions.save(Transaction(userId = user.id, type = TransactionType.CREDIT, nature = TransactionNature.INCOME, amount = BigDecimal("75000.00"),
            merchantName = "ACME", transactedAt = LocalDate.of(2026, 10, 7).atTime(10, 0).atZone(ist).toInstant()))
        bills.save(Bill(userId = user.id, billerName = "HDFC Credit Card", amount = BigDecimal("18450.00"), dueDate = LocalDate.of(2026, 10, 16)))
        bills.save(Bill(userId = user.id, billerName = "Later", amount = BigDecimal("100.00"), dueDate = LocalDate.of(2026, 10, 25)))
        anomalies.save(Anomaly(userId = user.id, transactionId = UUID.randomUUID(), type = AnomalyType.CARD_TESTING, confidenceScore = 0.9, reason = "₹1"))

        val b = assertNotNull(service.briefIfDue(user, prefs))
        assertEquals(LocalDate.of(2026, 10, 5), b.periodStart)
        assertEquals(LocalDate.of(2026, 10, 11), b.periodEnd)
        assertEquals(
            listOf(
                "Last week (5 Oct–11 Oct) you spent ₹4,100.50 — ₹2,100.50 more than the week before.",
                "Most went to Food (₹2,000), Shopping (₹1,500.50), Transport (₹600).",
                "Biggest payee: Swiggy (₹2,000 over 2 payments).",
                "You received ₹75,000.",
                "Coming up in the next 7 days: 1 due totalling ₹18,450 — first HDFC Credit Card on Fri 16 Oct.",
                "1 unusual charge waiting for your review."
            ).joinToString("\n"),
            b.content
        )
        assertEquals(BigDecimal("4100.50"), b.totalSpent)
        assertEquals(1, b.billsUpcoming)
        assertEquals("4100.50", b.contentStructured?.get("spent"))
        assertEquals("deterministic", b.modelUsed)

        val sent = notifications.findByUserAndType(user.id, NotificationType.BRIEFING)
        assertEquals("Your week: ₹4,100.50 spent", sent.first().title)
        assertEquals(mapOf("entity_type" to "briefing", "entity_id" to b.id.toString(), "action" to "view_briefing"), sent.first().data)

        assertNull(service.briefIfDue(user, prefs), "once per week")
        assertEquals(1L, briefings.countByUserId(user.id))
    }

    @Test
    fun `a quiet week says so, and singular and plural read right`() {
        val week = DateRange(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11))
        val quiet = BriefingFacts(week, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal("500"), emptyList(), null, emptyList(), 0, 2, listOf("Cult.fit"))
        assertEquals(
            "A quiet week (5 Oct–11 Oct): no spending recorded.\n2 subscriptions look worth a second look.\nUnused AutoPay: Cult.fit — revoke in your UPI app or bank if no longer needed.",
            BriefingWriter.write(quiet)
        )
        assertEquals("Your week: no spending recorded", BriefingWriter.headline(quiet))
        val first = quiet.copy(spent = BigDecimal("300"), previousSpent = BigDecimal.ZERO, flaggedSubscriptions = 1, dormantMandates = emptyList(), openAnomalies = 2)
        assertTrue(BriefingWriter.write(first).startsWith("Last week (5 Oct–11 Oct) you spent ₹300 — nothing the week before."))
        assertTrue("1 subscription looks worth a second look." in BriefingWriter.write(first))
        assertTrue("2 unusual charges waiting for your review." in BriefingWriter.write(first))
        assertTrue("₹200 less than the week before" in BriefingWriter.write(quiet.copy(spent = BigDecimal("300"))))
        assertTrue("the same as the week before" in BriefingWriter.write(quiet.copy(spent = BigDecimal("500"))))
    }

    @Test
    fun `not before the briefing time, not when switched off, not for a week before sign-up`() = runBlocking<Unit> {
        clock.now = Instant.parse("2026-10-12T02:00:00Z") // 07:30 IST Monday: last week's (28 Sep–4 Oct) is the due one
        val newcomer = users.save(User(email = "n@example.com", googleId = "n", createdAt = Instant.parse("2026-10-10T00:00:00Z")))
        assertNull(service.briefIfDue(newcomer, NotificationPreferences(userId = newcomer.id)), "joined after that week ended")
        assertNull(service.briefIfDue(user, prefs.copy(weeklyBriefing = false)))
        assertEquals(LocalDate.of(2026, 9, 28), service.briefIfDue(user, prefs)?.periodStart, "catches up the previous week")
    }

    @Test
    fun `the user's own day, time and zone decide`() = runBlocking<Unit> {
        val london = users.save(User(email = "l@example.com", googleId = "l", timezone = "Europe/London", createdAt = Instant.parse("2026-01-01T00:00:00Z")))
        val fri = NotificationPreferences(userId = london.id, briefingDay = Weekday.FRI, briefingTime = LocalTime.of(18, 0))
        assertEquals(LocalDate.of(2026, 10, 2), service.briefIfDue(london, fri)?.periodStart, "Fri 9 Oct 18:00 London has passed: 2–8 Oct")
        val bad = users.save(User(email = "b@example.com", googleId = "b", timezone = "Nope/Zone", createdAt = Instant.parse("2026-01-01T00:00:00Z")))
        assertEquals(LocalDate.of(2026, 10, 5), service.briefIfDue(bad, NotificationPreferences(userId = bad.id))?.periodStart, "invalid zone: India time")
    }

    @Test
    fun `the job briefs every active user whose briefing is due`() = runBlocking<Unit> {
        users.save(User(email = "off@example.com", googleId = "off", isActive = false, createdAt = Instant.parse("2026-01-01T00:00:00Z")))
        val job = WeeklyBriefingJob(users, prefsRepo, service)
        assertEquals(mapOf("users" to 1, "briefingsMade" to 1, "failed" to 0), job.run().counts)
        assertEquals(mapOf("users" to 1, "briefingsMade" to 0, "failed" to 0), job.run().counts)
        assertEquals("weekly-briefing", job.name.value)
        assertEquals(BriefingType.WEEKLY, briefings.findLatestByUserId(user.id)?.type)
        clock.advance(java.time.Duration.of(7, ChronoUnit.DAYS))
        assertEquals(1, job.run().counts["briefingsMade"], "next week")
    }

    @Test
    fun `rupees use Indian grouping, paise only when there are any, and a real minus`() {
        assertEquals("₹0", BigDecimal.ZERO.formatInr())
        assertEquals("₹999", BigDecimal("999").formatInr())
        assertEquals("₹1,000", BigDecimal("1000.00").formatInr())
        assertEquals("₹12,34,567.50", BigDecimal("1234567.5").formatInr())
        assertEquals("₹1,00,00,000", BigDecimal("10000000").formatInr())
        assertEquals("−₹2,100.25", BigDecimal("-2100.25").formatInr())
    }
}
