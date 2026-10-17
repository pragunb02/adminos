package dev.adminos.api.domain.financial

import dev.adminos.api.config.AppConfig
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
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** GET /api/v1/dues (AOS-503). */
class DuesRoutesTest {
    private val jwtService = JwtService(AppConfig.load().auth)
    private val user = User(id = UUID.randomUUID(), email = "pragun@example.in", googleId = "g-503")
    private val jwt = jwtService.generateAccessToken(user)
    private val bills = InMemoryBillRepository()
    private val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            routing { route("/api/v1") { duesRoutes(DuesService(bills, InMemorySubscriptionRepository(), dev.adminos.api.domain.financial.mandates.MandateService(dev.adminos.api.domain.financial.mandates.InMemoryMandateRepository(), InMemoryTransactionRepository()))) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.get(path: String, auth: Boolean = true): Pair<HttpStatusCode, JsonObject> {
        val res = client.get("/api/v1$path") { if (auth) cookie(ACCESS_TOKEN_COOKIE, jwt) }
        return res.status to Json.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    @Test
    fun `this month by default, with exact money strings`() = routeTest {
        runBlocking { bills.save(Bill(userId = user.id, billerName = "Tata Power", amount = BigDecimal("2350.5"), minimumDue = BigDecimal("100"), dueDate = today)) }
        val (status, body) = get("/dues")
        assertEquals(HttpStatusCode.OK, status)
        val data = body["data"]!!.jsonObject
        assertEquals(YearMonth.from(today).toString(), data["month"]!!.jsonPrimitive.content)
        assertEquals(today.toString(), data["today"]!!.jsonPrimitive.content)
        val item = data["items"]!!.jsonArray.single().jsonObject
        assertEquals("bill", item["kind"]!!.jsonPrimitive.content)
        assertEquals("2350.50", item["amount"]!!.jsonPrimitive.content)
        assertEquals("100.00", item["minimumDue"]!!.jsonPrimitive.content)
        assertEquals("2350.50", data["totals"]!!.jsonObject["open"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an explicit month, a bad month and a far month`() = routeTest {
        val next = YearMonth.from(today).plusMonths(1).toString()
        assertEquals(next, get("/dues?month=$next").second["data"]!!.jsonObject["month"]!!.jsonPrimitive.content)
        val (bad, e1) = get("/dues?month=October")
        assertEquals(HttpStatusCode.BadRequest, bad)
        assertTrue("YYYY-MM" in e1["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, get("/dues?month=2099-01").first)
        assertEquals(HttpStatusCode.BadRequest, get("/dues?month=1990-01").first)
    }

    @Test
    fun `signed-out calls are refused`() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/dues").status)
    }
}
