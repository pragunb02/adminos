package dev.adminos.api.domain.financial

import dev.adminos.api.domain.agent.*
import dev.adminos.api.domain.financial.FinancialCoverageSupport.IST
import dev.adminos.api.domain.financial.FinancialCoverageSupport.data
import dev.adminos.api.domain.financial.FinancialCoverageSupport.errorCode
import dev.adminos.api.domain.financial.FinancialCoverageSupport.getAs
import dev.adminos.api.domain.financial.FinancialCoverageSupport.isNull
import dev.adminos.api.domain.financial.FinancialCoverageSupport.money
import dev.adminos.api.domain.financial.FinancialCoverageSupport.newUser
import dev.adminos.api.domain.financial.FinancialCoverageSupport.routeTest
import dev.adminos.api.domain.financial.FinancialCoverageSupport.str
import dev.adminos.api.domain.financial.FinancialCoverageSupport.tokenFor
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Disabled
import java.math.BigDecimal
import java.time.*
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** GET /api/v1/dashboard/home (AOS-312): totals, action items, briefing and greeting, on a fixed clock. */
class FinancialDashboardRoutesCoverageTest {

    private val user = newUser()
    private val token = tokenFor(user)

    /** Friday 16 Oct 2026, 10:00 in India. */
    private val morning: Instant = LocalDateTime.of(2026, 10, 16, 10, 0).atZone(IST).toInstant()
    private val today: LocalDate = LocalDate.of(2026, 10, 16)

    private val transactions = InMemoryTransactionRepository()
    private val subscriptions = InMemorySubscriptionRepository()
    private val anomalies = InMemoryAnomalyRepository()
    private val briefings = InMemoryBriefingRepository()
    private val insights = InMemoryInsightRepository()

    private fun dashboardTest(clock: Clock = Clock.fixed(morning, IST), block: suspend io.ktor.server.testing.ApplicationTestBuilder.(InMemoryBillRepository) -> Unit) {
        val bills = InMemoryBillRepository()
        routeTest({
            dashboardRoutes(transactions, subscriptions, BillTrackingService(bills, transactions, dev.adminos.api.domain.identity.UserCalendar(dev.adminos.api.domain.identity.InMemoryUserRepository(), clock)), anomalies, briefings, insights, clock)
        }) { block(bills) }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.homeOf(t: String = token): JsonObject {
        val (status, body) = getAs(t, "/api/v1/dashboard/home")
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(true, body["success"]!!.jsonPrimitive.boolean)
        return body.data()
    }

    private fun JsonObject.summary() = this["summary"]!!.jsonObject
    private fun JsonObject.items(type: String) = this["actionItems"]!!.jsonArray.map { it.jsonObject }.filter { it.str("type") == type }

    private fun spend(amount: String, daysAgo: Long, nature: TransactionNature = TransactionNature.SPEND, owner: UUID = user.id) = Transaction(
        userId = owner, type = if (nature == TransactionNature.REFUND) TransactionType.REFUND else TransactionType.DEBIT,
        nature = nature, amount = BigDecimal(amount), category = TransactionCategory.FOOD, merchantName = "Swiggy",
        paymentMethod = PaymentMethod.UPI, upiVpa = "swiggy@hdfcbank", transactedAt = morning.minus(Duration.ofDays(daysAgo))
    )

    private fun bill(biller: String, due: LocalDate, amount: String = "1499.00", owner: UUID = user.id) =
        Bill(userId = owner, billType = BillType.CREDIT_CARD, billerName = biller, amount = BigDecimal(amount), dueDate = due)

    private fun briefing(status: BriefingStatus, content: String = "You spent ₹12,345.50 this week, mostly on food.", totalSpent: BigDecimal? = BigDecimal("12345.50")) = Briefing(
        userId = user.id, periodStart = today.minusDays(7), periodEnd = today.minusDays(1), content = content,
        totalSpent = totalSpent, status = status, modelUsed = "claude", promptVersion = "v1", tokensUsed = 900
    )

    @Test
    fun `weekly total nets refunds and ignores transfers and older spending`() = dashboardTest { _ ->
        runBlocking {
            transactions.save(spend("500.00", 2))
            transactions.save(spend("200.00", 1, TransactionNature.REFUND))
            transactions.save(spend("5000.00", 1, TransactionNature.TRANSFER))
            transactions.save(spend("1000.00", 8))
        }
        assertEquals(0, BigDecimal("300.00").compareTo(homeOf().summary().money("totalSpentThisWeek")))
    }

    @Test
    fun `an empty account has zero totals, no action items and no briefing`() = dashboardTest { _ ->
        val home = homeOf()
        val summary = home.summary()
        assertEquals(0, BigDecimal.ZERO.compareTo(summary.money("totalSpentThisWeek")))
        assertEquals(0, summary["activeSubscriptions"]!!.jsonPrimitive.int)
        assertEquals(0, summary["flaggedSubscriptions"]!!.jsonPrimitive.int)
        assertEquals(0, summary["upcomingBills"]!!.jsonPrimitive.int)
        assertEquals(0, summary["openAnomalies"]!!.jsonPrimitive.int)
        assertTrue(home["actionItems"]!!.jsonArray.isEmpty())
        assertTrue(home.isNull("latestBriefing"))
    }

    @Test
    fun `flagged subscriptions become warnings with their reason or a default description`() = dashboardTest { _ ->
        runBlocking {
            subscriptions.save(Subscription(userId = user.id, name = "Netflix", amount = BigDecimal("649"), billingCycle = BillingCycle.MONTHLY,
                isFlagged = true, flaggedReason = "No viewing in 60 days"))
            subscriptions.save(Subscription(userId = user.id, name = "Cult.fit", amount = BigDecimal("999"), billingCycle = BillingCycle.MONTHLY,
                isFlagged = true))
            subscriptions.save(Subscription(userId = user.id, name = "Spotify", amount = BigDecimal("119"), billingCycle = BillingCycle.MONTHLY))
        }
        val home = homeOf()
        assertEquals(3, home.summary()["activeSubscriptions"]!!.jsonPrimitive.int)
        assertEquals(2, home.summary()["flaggedSubscriptions"]!!.jsonPrimitive.int)
        val byTitle = home.items("subscription").associateBy { it.str("title") }
        assertEquals("No viewing in 60 days", byTitle.getValue("Potentially unused: Netflix").str("description"))
        assertEquals("This subscription may be unused", byTitle.getValue("Potentially unused: Cult.fit").str("description"))
        assertTrue(byTitle.values.all { it.str("severity") == "warning" && it.str("actionLabel") == "Review" })
    }

    @Test
    fun `bills due today and tomorrow are warnings, later ones this week are info`() = dashboardTest { bills ->
        val dueToday = runBlocking { bills.save(bill("HDFC Credit Card", today, "1499.00")) }
        val dueTomorrow = runBlocking { bills.save(bill("Tata Power", today.plusDays(1), "2350.75")) }
        val dueIn4 = runBlocking { bills.save(bill("Airtel Broadband", today.plusDays(4), "999.00")) }
        val home = homeOf()
        val byId = home.items("bill").associateBy { it.str("entityId") }
        assertEquals("Due today — ₹1499.00", byId.getValue(dueToday.id.toString()).str("description"))
        assertEquals("warning", byId.getValue(dueToday.id.toString()).str("severity"))
        assertEquals("Due tomorrow — ₹2350.75", byId.getValue(dueTomorrow.id.toString()).str("description"))
        assertEquals("warning", byId.getValue(dueTomorrow.id.toString()).str("severity"))
        assertEquals("Due in 4 days — ₹999.00", byId.getValue(dueIn4.id.toString()).str("description"))
        assertEquals("info", byId.getValue(dueIn4.id.toString()).str("severity"))
        assertEquals("HDFC Credit Card bill", byId.getValue(dueToday.id.toString()).str("title"))
    }

    @Test
    fun `bills due after seven days count as upcoming but are not action items`() = dashboardTest { bills ->
        runBlocking {
            bills.save(bill("SBI Card", today.plusDays(3)))
            bills.save(bill("LIC Premium", today.plusDays(20), "12000.00"))
        }
        val home = homeOf()
        assertEquals(2, home.summary()["upcomingBills"]!!.jsonPrimitive.int)
        assertEquals(listOf("SBI Card bill"), home.items("bill").map { it.str("title") })
    }

    @Test
    fun `open anomalies are critical action items and resolved ones are left out`() = dashboardTest { _ ->
        val open = runBlocking {
            anomalies.save(Anomaly(userId = user.id, transactionId = UUID.randomUUID(), type = AnomalyType.LARGE_AMOUNT,
                confidenceScore = 0.92, reason = "₹48,000 at an unknown merchant at 03:12"))
        }
        runBlocking {
            anomalies.save(Anomaly(userId = user.id, transactionId = UUID.randomUUID(), type = AnomalyType.DUPLICATE_CHARGE,
                confidenceScore = 0.7, reason = "Duplicate Zomato charge", status = AnomalyStatus.DISMISSED))
        }
        val home = homeOf()
        assertEquals(1, home.summary()["openAnomalies"]!!.jsonPrimitive.int)
        val item = home.items("anomaly").single()
        assertEquals(open.id.toString(), item.str("entityId"))
        assertEquals("critical", item.str("severity"))
        assertEquals("₹48,000 at an unknown merchant at 03:12", item.str("description"))
    }

    @Test
    fun `a generated briefing adds a read action and a summary trimmed to 300 characters`() = dashboardTest { _ ->
        val saved = runBlocking { briefings.save(briefing(BriefingStatus.GENERATED, content = "₹".repeat(10) + "x".repeat(400))) }
        val home = homeOf()
        val action = home.items("briefing").single()
        assertEquals("Read", action.str("actionLabel"))
        val summary = home["latestBriefing"]!!.jsonObject
        assertEquals(saved.id.toString(), summary.str("id"))
        assertEquals("2026-10-09", summary.str("periodStart"))
        assertEquals("2026-10-15", summary.str("periodEnd"))
        assertEquals(300, summary.str("content").length)
        assertEquals(0, BigDecimal("12345.50").compareTo(summary.money("totalSpent")))
    }

    @Test
    fun `a delivered briefing is summarised but not offered as a new action`() = dashboardTest { _ ->
        runBlocking { briefings.save(briefing(BriefingStatus.DELIVERED, totalSpent = null)) }
        val home = homeOf()
        assertTrue(home.items("briefing").isEmpty())
        val summary = home["latestBriefing"]!!.jsonObject
        assertTrue(summary.isNull("totalSpent"))
        assertEquals("You spent ₹12,345.50 this week, mostly on food.", summary.str("content"))
    }

    @Test
    fun `greeting is good morning before noon in India`() = dashboardTest { _ ->
        assertEquals("Good morning", homeOf().str("greeting"))
    }

    @Test
    fun `greeting is good afternoon between noon and five in India`() =
        dashboardTest(Clock.fixed(LocalDateTime.of(2026, 10, 16, 14, 30).atZone(IST).toInstant(), IST)) { _ ->
            assertEquals("Good afternoon", homeOf().str("greeting"))
        }

    @Test
    fun `greeting is good evening from five in India`() =
        dashboardTest(Clock.fixed(LocalDateTime.of(2026, 10, 16, 19, 0).atZone(IST).toInstant(), IST)) { _ ->
            assertEquals("Good evening", homeOf().str("greeting"))
        }

    @Test
    fun `another user's spending, subscriptions, bills, anomalies and briefings never appear`() = dashboardTest { bills ->
        val other = UUID.randomUUID()
        runBlocking {
            transactions.save(spend("750.00", 1, owner = other))
            subscriptions.save(Subscription(userId = other, name = "Hotstar", amount = BigDecimal("299"), billingCycle = BillingCycle.MONTHLY, isFlagged = true))
            bills.save(bill("ICICI Card", today.plusDays(2), owner = other))
            anomalies.save(Anomaly(userId = other, transactionId = UUID.randomUUID(), type = AnomalyType.ODD_HOURS, confidenceScore = 0.8, reason = "3am"))
            briefings.save(briefing(BriefingStatus.GENERATED).copy(userId = other))
        }
        val home = homeOf()
        assertEquals(0, BigDecimal.ZERO.compareTo(home.summary().money("totalSpentThisWeek")))
        assertEquals(0, home.summary()["flaggedSubscriptions"]!!.jsonPrimitive.int)
        assertEquals(0, home.summary()["upcomingBills"]!!.jsonPrimitive.int)
        assertEquals(0, home.summary()["openAnomalies"]!!.jsonPrimitive.int)
        assertTrue(home["actionItems"]!!.jsonArray.isEmpty())
        assertTrue(home.isNull("latestBriefing"))
    }

    @Test
    fun `without a session cookie the dashboard is 401 in the error envelope`() = dashboardTest { _ ->
        val (status, body) = getAs(null, "/api/v1/dashboard/home")
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertEquals("AUTH_002", body.errorCode())
    }
    @Test
    fun `greeting follows the user's own timezone`() {
        val nyUser = newUser(timezone = "America/New_York")
        // 09:00 in New York is 18:30 in India.
        val nineInNewYork = LocalDateTime.of(2026, 10, 16, 9, 0).atZone(ZoneId.of("America/New_York")).toInstant()
        dashboardTest(Clock.fixed(nineInNewYork, ZoneOffset.UTC)) { _ ->
            assertEquals("Good morning", homeOf(tokenFor(nyUser)).str("greeting"))
        }
    }
    @Test
    fun `a minute before India midnight a bill due that day is due today`() {
        val beforeMidnight = LocalDateTime.of(2026, 10, 16, 23, 59).atZone(IST).toInstant()
        dashboardTest(Clock.fixed(beforeMidnight, ZoneOffset.UTC)) { bills ->
            runBlocking { bills.save(bill("HDFC Credit Card", LocalDate.of(2026, 10, 16), "1499.00")) }
            assertEquals("Due today — ₹1499.00", homeOf().items("bill").single().str("description"))
        }
    }

    @Test
    fun `a minute after India midnight yesterday's bill is no longer listed and today's is due today`() {
        val afterMidnight = LocalDateTime.of(2026, 10, 17, 0, 1).atZone(IST).toInstant()
        dashboardTest(Clock.fixed(afterMidnight, ZoneOffset.UTC)) { bills ->
            runBlocking {
                bills.save(bill("BESCOM", LocalDate.of(2026, 10, 16), "800.00"))
                bills.save(bill("HDFC Credit Card", LocalDate.of(2026, 10, 17), "1499.00"))
            }
            val item = homeOf().items("bill").single()
            assertEquals("HDFC Credit Card bill", item.str("title"))
            assertEquals("Due today — ₹1499.00", item.str("description"))
        }
    }

    @Test
    fun `a bill due today in India says due today even when the server runs on UTC`() {
        // 01:30 on 17 Oct in India is still 16 Oct on a UTC server.
        val earlyMorning = LocalDateTime.of(2026, 10, 17, 1, 30).atZone(IST).toInstant()
        dashboardTest(Clock.fixed(earlyMorning, ZoneOffset.UTC)) { bills ->
            runBlocking { bills.save(bill("HDFC Credit Card", LocalDate.of(2026, 10, 17), "1499.00")) }
            assertEquals("Due today — ₹1499.00", homeOf().items("bill").single().str("description"))
        }
    }
}
