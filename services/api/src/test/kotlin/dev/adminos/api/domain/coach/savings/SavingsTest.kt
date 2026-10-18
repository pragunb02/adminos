package dev.adminos.api.domain.coach.savings

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.coach.CoachFixture.IST
import dev.adminos.api.domain.coach.CoachFixture.txn
import dev.adminos.api.domain.coach.CoachKit
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserRole
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
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AOS-908: the savings rate per month, the headline and its trend, and the SIP projection. "Today" is 18 Nov 2026 in India. */
class SavingsTest {
    private suspend fun CoachKit.money(merchant: String, amount: String, at: LocalDateTime, nature: TransactionNature, sub: String? = null,
                                       category: TransactionCategory = TransactionCategory.OTHER) =
        transactions.save(txn(merchant, amount, at, nature = nature, category = category, subcategory = sub, user = user))

    private suspend fun scenario(): CoachKit {
        val k = CoachKit()
        k.money("Acme Payroll", "100000.00", LocalDateTime.of(2026, 9, 1, 10, 0), TransactionNature.INCOME)
        k.money("Rent", "60000.00", LocalDateTime.of(2026, 9, 5, 10, 0), TransactionNature.SPEND)
        k.money("Acme Payroll", "100000.00", LocalDateTime.of(2026, 10, 1, 10, 0), TransactionNature.INCOME)
        k.money("Rent", "63950.00", LocalDateTime.of(2026, 10, 5, 10, 0), TransactionNature.SPEND)
        k.money("Zepto", "6050.00", LocalDateTime.of(2026, 10, 20, 22, 0), TransactionNature.SPEND, "groceries", TransactionCategory.SHOPPING)
        k.money("Savings account", "20000.00", LocalDateTime.of(2026, 10, 7, 10, 0), TransactionNature.TRANSFER)   // neither income nor spend
        k.money("Amazon", "500.00", LocalDateTime.of(2026, 10, 9, 10, 0), TransactionNature.REFUND, "ecommerce", TransactionCategory.SHOPPING) // reduces spend
        k.money("Zepto", "800.00", LocalDateTime.of(2026, 11, 3, 21, 0), TransactionNature.SPEND, "groceries", TransactionCategory.SHOPPING)  // November: no salary yet
        return k
    }

    @Test
    fun `the rate per month uses income only, refunds reduce spend, transfers count as neither`() = runBlocking<Unit> {
        val r = scenario().savings.report(CoachKit().user, IST, 3, Assumptions.DEFAULT)
        assertEquals(listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 10), YearMonth.of(2026, 11)), r.months.map { it.month })
        assertEquals(BigDecimal("0.4000"), r.months[0].rate)
        assertEquals(BigDecimal("0.3050"), r.months[1].rate)                // (1,00,000 − 69,500) ÷ 1,00,000
        assertNull(r.months[2].rate, "no income yet this month: no rate, not −∞")
        assertTrue(r.months[2].partial)
        assertEquals(YearMonth.of(2026, 10), r.headline?.month, "the headline falls back to the latest month with income")
        assertEquals(YearMonth.of(2026, 9), r.previous?.month)
    }

    @Test
    fun `last month's leaks as a SIP over 5 and 10 years, with its assumptions`() = runBlocking<Unit> {
        val p = scenario().savings.report(CoachKit().user, IST, 12, Assumptions.DEFAULT).projection!!
        assertEquals(YearMonth.of(2026, 10), p.month)
        assertEquals(0, p.monthlyAmount.compareTo(BigDecimal("5550")), "₹6,050 of quick commerce less a ₹500 shopping refund")
        assertEquals(listOf(5, 10), p.horizons.map { it.years })
        assertEquals(BigDecimal("457799"), p.horizons[0].futureValue)
        assertEquals(BigDecimal("342094"), p.horizons[0].inTodaysMoney)
        assertEquals(BigDecimal("333000"), p.horizons[0].contributed)
        assertEquals(BigDecimal("1289482"), p.horizons[1].futureValue)
        assertEquals(BigDecimal("720040"), p.horizons[1].inTodaysMoney)
    }

    @Test
    fun `the SIP formula, including a zero return, and its bounds`() {
        assertEquals(BigDecimal("82486"), Sip.futureValue(BigDecimal(1000), BigDecimal(12), 5))
        assertEquals(BigDecimal("74970"), Sip.futureValue(BigDecimal(1000), BigDecimal("8.5"), 5))
        assertEquals(BigDecimal("60000"), Sip.futureValue(BigDecimal(1000), BigDecimal.ZERO, 5))
        assertEquals(BigDecimal("1000"), Sip.inTodaysMoney(BigDecimal(1000), BigDecimal.ZERO, 10))
        assertFailsWith<IllegalArgumentException> { Sip.futureValue(BigDecimal(1000), BigDecimal(12), 0) }
        assertFailsWith<IllegalArgumentException> { Assumptions(BigDecimal(31), BigDecimal(6)) }
        assertFailsWith<IllegalArgumentException> { Assumptions(BigDecimal(12), BigDecimal(-1)) }
        assertTrue(Sip.FORMULA.contains("(1 + r)^n"))
    }

    @Test
    fun `too little data, zero income and no leaks`() = runBlocking<Unit> {
        val k = CoachKit()
        val empty = k.savings.report(k.user, IST, 12, Assumptions.DEFAULT)
        assertNull(empty.headline); assertNull(empty.projection); assertEquals(12, empty.months.size)
        k.money("Rent", "1000.00", LocalDateTime.of(2026, 10, 5, 10, 0), TransactionNature.SPEND)
        val spendOnly = k.savings.report(k.user, IST, 3, Assumptions.DEFAULT)
        assertNull(spendOnly.headline, "spending without income has no rate")
        assertNull(spendOnly.projection, "no leaks last month, nothing to project")
        // Spending more than income: a negative rate, shown as it is.
        k.money("Acme Payroll", "500.00", LocalDateTime.of(2026, 10, 1, 10, 0), TransactionNature.INCOME)
        assertEquals(BigDecimal("-1.0000"), k.savings.report(k.user, IST, 3, Assumptions.DEFAULT).headline?.rate)
        assertFailsWith<IllegalArgumentException> { runBlocking { k.savings.report(k.user, IST, 2, Assumptions.DEFAULT) } }
    }

    // ── Route ──
    private val jwtService = JwtService(AppConfig.load().auth)

    private fun routeTest(kit: CoachKit, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureRequestBodyLimit(); configureAuthentication(jwtService)
            routing { route("/api/v1") { savingsRoutes(kit.savings) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.get(path: String, who: User): Pair<HttpStatusCode, JsonObject> {
        val res = client.get(path) { cookie(ACCESS_TOKEN_COOKIE, jwtService.generateAccessToken(who)) }
        return res.status to Json.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    @Test
    fun `the route returns exact strings, the formula, the defaults and editable assumptions`() {
        val k = runBlocking { scenario() }
        routeTest(k) {
            val (status, body) = get("/api/v1/coach/savings?months=3&annualReturnPct=8.5&inflationPct=5", k.owner)
            assertEquals(HttpStatusCode.OK, status)
            val d = body.getValue("data").jsonObject
            assertEquals("0.3050", d.getValue("headline").jsonObject.getValue("rate").jsonPrimitive.content)
            assertEquals("false", d.getValue("tooLittleData").jsonPrimitive.content)
            val p = d.getValue("projection").jsonObject
            assertEquals("8.5", p.getValue("annualReturnPct").jsonPrimitive.content)
            assertTrue(p.getValue("formula").jsonPrimitive.content.startsWith("FV = P"))
            assertEquals("12", d.getValue("defaults").jsonObject.getValue("annualReturnPct").jsonPrimitive.content)
            assertEquals(JsonNull, d.getValue("months").jsonArray[2].jsonObject.getValue("rate"))
        }
    }

    @Test
    fun `bad parameters are 400s, another user sees too little data, and a session is required`() {
        val k = runBlocking { scenario() }
        val stranger = User(id = UUID.randomUUID(), email = "s@example.com", googleId = "g-9", role = UserRole.OWNER, timezone = "Asia/Kolkata")
        routeTest(k) {
            for ((q, name) in listOf("months=2" to "months", "months=abc" to "months", "months=99" to "months", "annualReturnPct=31" to "annualReturnPct",
                                     "annualReturnPct=-1" to "annualReturnPct", "inflationPct=1e2" to "inflationPct", "inflationPct=%3Cscript%3E" to "inflationPct")) {
                val (status, body) = get("/api/v1/coach/savings?$q", k.owner)
                assertEquals(HttpStatusCode.BadRequest, status, q)
                assertTrue(body.getValue("error").jsonObject.getValue("message").jsonPrimitive.content.contains(name), "$q → $body")
            }
            assertEquals("true", get("/api/v1/coach/savings", stranger).second.getValue("data").jsonObject.getValue("tooLittleData").jsonPrimitive.content)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/coach/savings").status)
        }
    }
}
