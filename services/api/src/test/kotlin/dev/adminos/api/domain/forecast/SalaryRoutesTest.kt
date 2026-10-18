package dev.adminos.api.domain.forecast

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.financial.InMemoryBillRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionType
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** GET and PUT /api/v1/forecast/salary (AOS-722). */
class SalaryRoutesTest {
    private val jwtService = JwtService(AppConfig.load().auth)
    private val user = User(id = UUID.randomUUID(), email = "owner@example.com", googleId = "g-722", timezone = "Asia/Kolkata")
    private val jwt = jwtService.generateAccessToken(user)
    private val ist = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-10-20T04:30:00Z")
    private val transactions = InMemoryTransactionRepository()
    private val choices = InMemorySalaryChoiceRepository()
    private val service = SalaryChoiceService(choices, InMemoryForecastHistoryRepository(transactions, InMemoryBillRepository()), clock = Clock.fixed(now, ZoneOffset.UTC))
    private val limiter = RateLimiter(maxRequests = 40, windowSeconds = 60)

    private fun routeTest(limiter: RateLimiter = this.limiter, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            routing { route("/api/v1") { salaryChoiceRoutes(service, limiter) } }
        }
        block()
    }

    private fun salaries() = runBlocking {
        listOf("2026-07-01", "2026-08-01", "2026-09-01", "2026-10-01").forEach { day ->
            transactions.save(Transaction(userId = user.id, type = TransactionType.CREDIT, amount = BigDecimal("85000"), merchantName = "ACME TECHNOLOGIES",
                transactedAt = LocalDate.parse(day).atTime(9, 0).atZone(ist).toInstant()))
        }
    }

    private suspend fun ApplicationTestBuilder.send(body: String, type: ContentType = ContentType.Application.Json) =
        client.put("/api/v1/forecast/salary") { cookie(ACCESS_TOKEN_COOKIE, jwt); contentType(type); setBody(body) }

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.data() = json()["data"]!!.jsonObject
    private suspend fun HttpResponse.code() = json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    @Test
    fun `GET shows the detected salary with exact money and no choice yet`() = routeTest {
        salaries()
        val res = client.get("/api/v1/forecast/salary") { cookie(ACCESS_TOKEN_COOKIE, jwt) }
        assertEquals(HttpStatusCode.OK, res.status)
        val expected = Json.parseToJsonElement("""
            {"detected":{"payer":"ACME TECHNOLOGIES","usualDay":1,"typicalAmount":"85000.00","lastAmount":"85000.00","lastReceivedOn":"2026-10-01",
             "expectedOn":"2026-11-01","credits":4},"detectedReason":null,"choice":null}
        """)
        assertEquals(expected, res.data())
    }

    @Test
    fun `GET says why when nothing is detected`() = routeTest {
        val data = client.get("/api/v1/forecast/salary") { cookie(ACCESS_TOKEN_COOKIE, jwt) }.data()
        assertEquals(JsonNull, data["detected"])
        assertEquals("no_regular_credit", data["detectedReason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `PUT confirm stores the detected payer and day`() = routeTest {
        salaries()
        val res = send("""{"action":"confirm"}""")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val expected = Json.parseToJsonElement("""{"payer":"ACME TECHNOLOGIES","day":1,"amount":"85000.00","kind":"confirmed","updatedAt":"2026-10-20T04:30:00Z"}""")
        assertEquals(expected, res.data()["choice"])
        assertEquals(expected, client.get("/api/v1/forecast/salary") { cookie(ACCESS_TOKEN_COOKIE, jwt) }.data()["choice"])
    }

    @Test
    fun `PUT confirm with nothing detected is 409 and stores nothing`() = routeTest {
        val res = send("""{"action":"confirm"}""")
        assertEquals(HttpStatusCode.Conflict, res.status)
        assertEquals("SALARY_001", res.code())
        assertEquals(null, choices.find(user.id))
    }

    @Test
    fun `PUT confirm with values is refused, the client never supplies them`() = routeTest {
        salaries()
        val res = send("""{"action":"confirm","payer":"Evil","day":5,"amount":"1"}""")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("VALIDATION_001", res.code())
        assertEquals(null, choices.find(user.id))
    }

    @Test
    fun `PUT correct stores the owner's payer, day and amount`() = routeTest {
        val res = send("""{"action":"correct","payer":"  Globex Freelance ","day":15,"amount":"40000.5"}""")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val choice = res.data()["choice"]!!.jsonObject
        assertEquals("Globex Freelance", choice["payer"]!!.jsonPrimitive.content)
        assertEquals(15, choice["day"]!!.jsonPrimitive.int)
        assertEquals("40000.50", choice["amount"]!!.jsonPrimitive.content)
        assertEquals("corrected", choice["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun `PUT correct with no payer means credits without a name`() = routeTest {
        val res = send("""{"action":"correct","payer":null,"day":1,"amount":"85000"}""")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(JsonNull, res.data()["choice"]!!.jsonObject["payer"])
    }

    @Test
    fun `bad values are 400 with a validation code and never echoed`() = routeTest {
        val bad = listOf(
            """{"action":"delete"}""", """{"action":"CONFIRM"}""", """{"action":""}""",
            """{"action":"correct","payer":"ACME","day":1}""", """{"action":"correct","payer":"ACME","amount":"85000"}""",
            """{"action":"correct","payer":"ACME","day":0,"amount":"85000"}""", """{"action":"correct","payer":"ACME","day":32,"amount":"85000"}""",
            """{"action":"correct","payer":"ACME","day":-5,"amount":"85000"}""",
            """{"action":"correct","payer":"ACME","day":1,"amount":"1e5"}""", """{"action":"correct","payer":"ACME","day":1,"amount":"-1"}""",
            """{"action":"correct","payer":"ACME","day":1,"amount":"0"}""", """{"action":"correct","payer":"ACME","day":1,"amount":"0.00"}""",
            """{"action":"correct","payer":"ACME","day":1,"amount":"12.345"}""", """{"action":"correct","payer":"ACME","day":1,"amount":"99999999999"}""",
            """{"action":"correct","payer":"ACME","day":1,"amount":"1000000000.01"}""", """{"action":"correct","payer":"ACME","day":1,"amount":"lots"}""",
            """{"action":"correct","payer":"ACME","day":1,"amount":" 85000"}""", """{"action":"correct","payer":"ACME","day":1,"amount":"85,000"}""",
            """{"action":"correct","payer":"ACME","day":1,"amount":"८५०००"}""", """{"action":"correct","payer":"ACME","day":1,"amount":"NaN"}""",
            """{"action":"correct","payer":"${"Z".repeat(101)}","day":1,"amount":"85000"}""",
            """{"action":"correct","payer":"","day":1,"amount":"85000"}""", """{"action":"correct","payer":"   ","day":1,"amount":"85000"}""",
            """{"action":"correct","payer":"zq\u202Egnirts","day":1,"amount":"85000"}""", """{"action":"correct","payer":"zq\u200Bx","day":1,"amount":"85000"}""",
            """{"action":"correct","payer":"zq\nx","day":1,"amount":"85000"}""", """{"action":"correct","payer":"zq\u0000x","day":1,"amount":"85000"}""",
        )
        val wrong = bad.mapNotNull { body ->
            val res = send(body)
            val text = res.bodyAsText()
            when {
                res.status != HttpStatusCode.BadRequest -> "$body → ${res.status} $text"
                !res.code().startsWith("VALIDATION_") -> "$body → ${res.code()}"
                "zq" in text || "ZZZZ" in text || "Exception" in text -> "$body echoed: $text"
                else -> null
            }
        }
        assertEquals(emptyList(), wrong)
        assertEquals(null, choices.find(user.id))
    }

    @Test
    fun `a day or amount of the wrong JSON type is a malformed body`() = routeTest {
        listOf("""{"action":"correct","day":1.5,"amount":"1"}""",
            """{"action":"correct","day":99999999999,"amount":"1"}""", """{"action":"correct","day":1,"amount":85000}""", """{"day":1}""").forEach { body ->
            val res = send(body)
            assertEquals(HttpStatusCode.BadRequest, res.status, body)
            assertEquals("VALIDATION_004", res.code(), body)
        }
    }

    @Test
    fun `hostile but printable payers are stored and returned as plain JSON text`() = routeTest {
        val hostile = "<img src=x onerror=alert(1)>'; DROP TABLE salary_choices; --"
        val res = send(buildJsonObject { put("action", "correct"); put("payer", hostile); put("day", 1); put("amount", "85000") }.toString())
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(hostile, res.data()["choice"]!!.jsonObject["payer"]!!.jsonPrimitive.content)
        assertEquals(hostile, choices.find(user.id)?.payer)
    }

    @Test
    fun `only JSON is read`() = routeTest {
        for (type in listOf(ContentType.Text.Plain, ContentType.Application.FormUrlEncoded, ContentType.MultiPart.FormData)) {
            val res = send("""{"action":"correct","payer":"ACME","day":1,"amount":"1"}""", type)
            assertEquals(HttpStatusCode.UnsupportedMediaType, res.status, type.toString())
            assertEquals("VALIDATION_005", res.code())
        }
        assertEquals(null, choices.find(user.id))
    }

    @Test
    fun `changes are rate limited per user`() = routeTest(limiter = RateLimiter(maxRequests = 2, windowSeconds = 60)) {
        repeat(2) { assertEquals(HttpStatusCode.OK, send("""{"action":"correct","payer":"ACME","day":1,"amount":"1"}""").status) }
        val res = send("""{"action":"correct","payer":"ACME","day":2,"amount":"1"}""")
        assertEquals(HttpStatusCode.TooManyRequests, res.status)
        assertEquals("RATE_001", res.code())
        assertEquals(1, choices.find(user.id)?.day, "the refused change wasn't stored")
        val stranger = jwtService.generateAccessToken(User(id = UUID.randomUUID(), email = "x@example.com", googleId = "g-x"))
        val theirs = client.put("/api/v1/forecast/salary") { cookie(ACCESS_TOKEN_COOKIE, stranger); contentType(ContentType.Application.Json); setBody("""{"action":"correct","day":1,"amount":"1"}""") }
        assertEquals(HttpStatusCode.OK, theirs.status, "another user's limit is their own")
    }

    @Test
    fun `without a session both routes are 401`() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/forecast/salary").status)
        val res = client.put("/api/v1/forecast/salary") { contentType(ContentType.Application.Json); setBody("""{"action":"correct","day":1,"amount":"1"}""") }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        val forged = client.get("/api/v1/forecast/salary") { cookie(ACCESS_TOKEN_COOKIE, jwt.dropLast(3) + "abc") }
        assertEquals(HttpStatusCode.Unauthorized, forged.status)
    }

    @Test
    fun `the user comes from the session, never the body`() = routeTest {
        val other = UUID.randomUUID()
        val res = send("""{"action":"correct","payer":"ACME","day":1,"amount":"1","userId":"$other"}""")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(null, choices.find(other))
        assertTrue(choices.find(user.id) != null)
        assertFalse(other.toString() in res.bodyAsText())
    }
}
