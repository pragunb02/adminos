package dev.adminos.api.domain.forecast

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.analytics.DateRange
import dev.adminos.api.domain.financial.DuesService
import dev.adminos.api.domain.financial.InMemoryBillRepository
import dev.adminos.api.domain.financial.InMemorySubscriptionRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.financial.mandates.InMemoryMandateRepository
import dev.adminos.api.domain.financial.mandates.MandateService
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GET /api/v1/forecast (AOS-714). */
class ForecastRoutesTest {
    private val jwtService = JwtService(AppConfig.load().auth)
    private val user = User(id = UUID.randomUUID(), email = "pragun@example.com", googleId = "g-714", timezone = "Asia/Kolkata")
    private val jwt = jwtService.generateAccessToken(user)
    private val transactions = InMemoryTransactionRepository()
    private val bills = InMemoryBillRepository()
    private val subscriptions = InMemorySubscriptionRepository()
    private val ist = ZoneId.of("Asia/Kolkata")
    private val clock = Clock.fixed(Instant.parse("2026-10-20T04:30:00Z"), ZoneOffset.UTC)
    private val limiter = dev.adminos.api.infrastructure.plugins.RateLimiter(maxRequests = 5, windowSeconds = 60)
    private val balances = dev.adminos.api.domain.balances.BalanceService(dev.adminos.api.domain.balances.InMemoryBalanceRepository(), clock)
    private val service = ForecastService(InMemoryForecastHistoryRepository(transactions, bills),
        DuesService(bills, subscriptions, MandateService(InMemoryMandateRepository(), transactions)), subscriptions, bills, balances, clock = clock)

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            routing { route("/api/v1") { forecastRoutes(service, limiter) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.data(token: String = jwt): JsonObject {
        val res = client.get("/api/v1/forecast") { cookie(ACCESS_TOKEN_COOKIE, token) }
        assertEquals(HttpStatusCode.OK, res.status)
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject["data"]!!.jsonObject
    }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    @Test
    fun `a new user is told there is not enough history`() = routeTest {
        val data = data()
        assertEquals("not_enough_history", data.str("status"))
        assertEquals("2026-10-20", data.str("today"))
        assertEquals(90, data["history"]!!.jsonObject["neededDays"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, data["monthEnd"])
    }

    @Test
    fun `a ready forecast lists its parts with exact money and the salary outlook`() = routeTest {
        runBlocking {
            var day = LocalDate.of(2026, 6, 1)
            while (!day.isAfter(LocalDate.of(2026, 10, 20))) {
                transactions.save(Transaction(userId = user.id, type = TransactionType.DEBIT, amount = BigDecimal("300"), merchantName = "Chai", transactedAt = day.atTime(12, 0).atZone(ist).toInstant()))
                if (day.dayOfMonth == 1) transactions.save(Transaction(userId = user.id, type = TransactionType.CREDIT, amount = BigDecimal("85000"), merchantName = "ACME", transactedAt = day.atTime(9, 0).atZone(ist).toInstant()))
                day = day.plusDays(1)
            }
        }
        val data = data()
        assertEquals("ready", data.str("status"))
        val month = data["monthEnd"]!!.jsonObject
        assertEquals("2026-10", month.str("month"))
        assertEquals("6000.00", month.str("spentSoFar"))
        assertEquals("9300.00", month.str("total"))
        assertEquals(11, month["daysLeft"]!!.jsonPrimitive.int)
        val parts = month["parts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("bills", "renewals", "mandates", "projected_bills", "everyday"), parts.map { it.str("kind") })
        val everyday = parts.last()
        assertEquals("300.00", everyday.str("weekdayRate"))
        assertEquals(8, everyday["weekdays"]!!.jsonPrimitive.int)
        assertEquals(3, everyday["weekendDays"]!!.jsonPrimitive.int)
        assertEquals("weekly_median", everyday.str("method"))
        val salary = data["salary"]!!.jsonObject
        assertEquals("expected", salary.str("status"))
        assertEquals("2026-11-01", salary.str("expectedOn"))
        val until = salary["untilSalary"]!!.jsonObject
        assertEquals("2026-10-21", until.str("from"))
        assertEquals("2026-10-31", until.str("to"))
        assertEquals("75700.00", until.str("left"))
        assertEquals("salary", until.str("basis"))
        assertEquals("2026-10-01", until.str("spentFrom"))
        assertEquals(JsonNull, until["balance"])
    }

    @Test
    fun `money left starts from recent balances and says how, listing the stale ones (AOS-720)`() = routeTest {
        runBlocking {
            var day = LocalDate.of(2026, 6, 1)
            while (!day.isAfter(LocalDate.of(2026, 10, 20))) {
                transactions.save(Transaction(userId = user.id, type = TransactionType.DEBIT, amount = BigDecimal("300"), merchantName = "Chai", transactedAt = day.atTime(12, 0).atZone(ist).toInstant()))
                if (day.dayOfMonth == 1) transactions.save(Transaction(userId = user.id, type = TransactionType.CREDIT, amount = BigDecimal("85000"), merchantName = "ACME", transactedAt = day.atTime(9, 0).atZone(ist).toInstant()))
                day = day.plusDays(1)
            }
            fun ref(s: String) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
            balances.record(dev.adminos.api.domain.balances.BalanceObservation(user.id, "hdfc", "1234", BigDecimal("50000.00"),
                Instant.parse("2026-10-18T06:00:00Z"), dev.adminos.api.domain.balances.BalanceSource.SMS, ref("a")))
            balances.record(dev.adminos.api.domain.balances.BalanceObservation(user.id, "sbi", "5678", BigDecimal("700.00"),
                Instant.parse("2026-10-01T06:00:00Z"), dev.adminos.api.domain.balances.BalanceSource.STATEMENT, ref("b")))
        }
        val until = data()["salary"]!!.jsonObject["untilSalary"]!!.jsonObject
        assertEquals("balance", until.str("basis"))
        assertEquals("50000.00", until.str("balance"))
        assertEquals("2026-10-18", until.str("balanceAsOf"))
        assertEquals("2026-10-18", until.str("spentFrom"))
        assertEquals("900.00", until.str("spentSince"))
        assertEquals("45800.00", until.str("left"))
        assertEquals(7, until["balanceMaxAgeDays"]!!.jsonPrimitive.int)
        val account = until["accounts"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("hdfc", "1234", "50000.00", "2026-10-18", "sms"), listOf("bank", "last4", "amount", "observedOn", "source").map { account.str(it) })
        val stale = until["notCounted"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("5678", "stale", "statement"), listOf("last4", "reason", "source").map { stale.str(it) })
    }

    @Test
    fun `signed-out calls and forged tokens are refused`() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/forecast").status)
        val forged = client.get("/api/v1/forecast") { cookie(ACCESS_TOKEN_COOKIE, jwt.dropLast(3) + "abc") }
        assertEquals(HttpStatusCode.Unauthorized, forged.status)
    }

    @Test
    fun `another user's money never reaches my forecast`() = routeTest {
        val other = User(id = UUID.randomUUID(), email = "other@example.com", googleId = "g-715", timezone = "Asia/Kolkata")
        runBlocking {
            var day = LocalDate.of(2026, 6, 1)
            while (!day.isAfter(LocalDate.of(2026, 10, 20))) {
                transactions.save(Transaction(userId = other.id, type = TransactionType.DEBIT, amount = BigDecimal("999"), merchantName = "Theirs", transactedAt = day.atTime(12, 0).atZone(ist).toInstant()))
                day = day.plusDays(1)
            }
        }
        assertEquals("not_enough_history", data().str("status"))
        assertEquals("ready", data(jwtService.generateAccessToken(other)).str("status"))
    }

    @Test
    fun `forecasts are rate limited per user`() = routeTest {
        repeat(5) { assertEquals(HttpStatusCode.OK, client.get("/api/v1/forecast") { cookie(ACCESS_TOKEN_COOKIE, jwt) }.status) }
        val res = client.get("/api/v1/forecast") { cookie(ACCESS_TOKEN_COOKIE, jwt) }
        assertEquals(HttpStatusCode.TooManyRequests, res.status)
        assertEquals("RATE_001", Json.parseToJsonElement(res.bodyAsText()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `late and unknown salaries map to their own statuses`() {
        val salary = DetectedSalary("ACME", BigDecimal("85000"), LocalDate.of(2026, 9, 1), BigDecimal("85000"), LocalDate.of(2026, 10, 1), 4)
        val monthEnd = MonthEndForecast(java.time.YearMonth.of(2026, 10), LocalDate.of(2026, 10, 31), BigDecimal("100"), null, emptyList(),
            ForecastRange(BigDecimal("100"), BigDecimal("100"), 80, 3), 0)
        val late = Forecast.Ready(monthEnd, SalaryOutlook.Late(salary)).toResponse()
        assertEquals("late", late.salary?.status)
        assertNull(late.salary?.untilSalary)
        assertEquals(0, late.monthEnd?.daysLeft)
        assertEquals("100.00", late.monthEnd?.range?.low)
        val unknown = Forecast.Ready(monthEnd, SalaryOutlook.Unknown(NoSalaryReason.STOPPED, "ACME", LocalDate.of(2026, 8, 1))).toResponse()
        assertEquals("unknown", unknown.salary?.status)
        assertEquals("stopped", unknown.salary?.reason)
        assertEquals("2026-08-01", unknown.salary?.lastReceivedOn)
        val notEnough = Forecast.NotEnoughHistory(LocalDate.of(2026, 10, 20), 40, 90, LocalDate.of(2026, 12, 9)).toResponse()
        assertEquals("2026-12-09", notEnough.history?.readyOn)
        val dues = ComponentEstimate.Dues(DueSource.MANDATE, listOf(KnownDue(DueSource.MANDATE, "SIP", LocalDate.of(2026, 10, 25), BigDecimal("5000"), overdue = false)))
        val withDues = Forecast.Ready(monthEnd.copy(parts = listOf(dues), remaining = DateRange(LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 31))), SalaryOutlook.Late(salary)).toResponse()
        assertEquals("mandates", withDues.monthEnd?.parts?.single()?.kind)
        assertEquals("5000.00", withDues.monthEnd?.parts?.single()?.items?.single()?.amount)
        assertTrue(withDues.monthEnd?.daysLeft == 11)
    }
    @Test
    fun `a projected bill is its own part with its type and how long it was seen`() = routeTest {
        runBlocking {
            var day = LocalDate.of(2026, 6, 1)
            while (!day.isAfter(LocalDate.of(2026, 10, 20))) {
                transactions.save(Transaction(userId = user.id, type = TransactionType.DEBIT, amount = BigDecimal("300"), merchantName = "Chai", transactedAt = day.atTime(12, 0).atZone(ist).toInstant()))
                day = day.plusDays(1)
            }
            listOf(7, 8, 9).forEach { m ->
                bills.save(dev.adminos.api.domain.financial.Bill(userId = user.id, billerName = "Sample Power", billType = dev.adminos.api.domain.financial.BillType.ELECTRICITY,
                    amount = BigDecimal("1200"), dueDate = LocalDate.of(2026, m, 25), status = dev.adminos.api.domain.financial.BillStatus.PAID))
            }
        }
        val month = data()["monthEnd"]!!.jsonObject
        val part = month["parts"]!!.jsonArray.map { it.jsonObject }.single { it.str("kind") == "projected_bills" }
        assertEquals("1200.00", part.str("amount"))
        val item = part["items"]!!.jsonArray.single().jsonObject
        assertEquals("Sample Power", item.str("name"))
        assertEquals("2026-10-25", item.str("date"))
        assertEquals("1200.00", item.str("amount"))
        assertEquals(false, item["overdue"]!!.jsonPrimitive.boolean)
        assertEquals("electricity", item.str("billType"))
        assertEquals(3, item["monthsSeen"]!!.jsonPrimitive.int)
        assertEquals("2026-07", item.str("seenFrom"))
        assertEquals(JsonNull, part["weekdayRate"])
        assertEquals("10500.00", month.str("total"))
    }
}
