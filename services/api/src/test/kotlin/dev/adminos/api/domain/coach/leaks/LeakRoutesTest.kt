package dev.adminos.api.domain.coach.leaks

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.coach.CoachFixture
import dev.adminos.api.domain.coach.lenses.InMemoryLensOverrideRepository
import dev.adminos.api.domain.coach.lenses.LensMatcher
import dev.adminos.api.domain.coach.lenses.LensService
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
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
import java.net.URLEncoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** /api/v1/coach/leaks contract (AOS-902): exact strings, defaults, drill-down envelope, 400s, auth, isolation. */
class LeakRoutesTest {
    private val jwtService = JwtService(AppConfig.load().auth)
    private val user = User(id = CoachFixture.USER, email = "me@example.com", googleId = "g-1", role = UserRole.OWNER, timezone = "Asia/Kolkata")
    private val other = User(id = UUID.randomUUID(), email = "other@example.com", googleId = "g-2", role = UserRole.OWNER, timezone = "Asia/Kolkata")
    private val txns = InMemoryTransactionRepository().also { r -> runBlocking { LeakFixture.week(user.id).forEach { r.save(it) } } }
    private val clock = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC)
    private val service = LeakService(txns, LensService(LensMatcher(), InMemoryLensOverrideRepository()), clock)

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureRequestBodyLimit(); configureAuthentication(jwtService)
            routing { route("/api/v1") { leakRoutes(service) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.get(path: String, who: User = user): Pair<HttpStatusCode, JsonObject> {
        val res = client.get(path) { cookie(ACCESS_TOKEN_COOKIE, jwtService.generateAccessToken(who)) }
        return res.status to Json.parseToJsonElement(res.bodyAsText()).jsonObject
    }
    private fun JsonObject.data() = getValue("data")
    private fun JsonElement.str(key: String) = jsonObject.getValue(key).jsonPrimitive.content
    private fun JsonElement.obj(key: String) = jsonObject.getValue(key).jsonObject
    private fun JsonObject.lens(id: String) = data().jsonObject.getValue("lenses").jsonArray.first { it.str("id") == id }

    @Test
    fun `the report has exact strings per lens and merchant`() = routeTest {
        val (status, body) = get("/api/v1/coach/leaks?from=2026-10-12&to=2026-10-18")
        assertEquals(HttpStatusCode.OK, status)
        val d = body.data()
        assertEquals("2026-10-05", d.obj("previousPeriod").str("from"))
        assertEquals("22:00", d.obj("lateNight").str("from"))
        assertEquals("951.00", d.obj("total").str("spent"))
        val qc = body.lens("quick_commerce").obj("numbers")
        assertEquals("1200.00", qc.str("spent")); assertEquals("433.33", qc.str("averageOrder"))
        assertEquals("0.6667", qc.obj("lateNight").str("share")); assertEquals("140.0", qc.str("changePct"))
        assertEquals(listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun"), qc.getValue("weekdays").jsonArray.map { it.str("day") })
        assertTrue(qc.getValue("spent").jsonPrimitive.isString)
        assertEquals("Zepto", body.lens("quick_commerce").jsonObject.getValue("merchants").jsonArray[0].str("merchant"))
        val shopping = body.lens("shopping").obj("numbers")
        assertEquals("-999.00", shopping.str("spent"))
        assertEquals(JsonNull, shopping.getValue("averageOrder"))
    }

    @Test
    fun `the default range is this month so far`() = routeTest {
        val d = get("/api/v1/coach/leaks").second.data()
        assertEquals("2026-10-01", d.obj("period").str("from")); assertEquals("2026-10-18", d.obj("period").str("to"))
    }

    @Test
    fun `the drill-down uses the transactions envelope with the full count`() = routeTest {
        val (status, body) = get("/api/v1/coach/leaks/transactions?from=2026-10-12&to=2026-10-18&lens=quick_commerce&lateNight=true")
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(listOf("300.00", "600.00"), body.getValue("data").jsonArray.map { it.str("amount") })
        assertEquals("2", body.obj("pagination").str("total"))
        val tue = get("/api/v1/coach/leaks/transactions?from=2026-10-12&to=2026-10-18&lens=quick_commerce&weekday=tue").second
        assertEquals(1, tue.getValue("data").jsonArray.size)
        val all = get("/api/v1/coach/leaks/transactions?from=2026-10-12&to=2026-10-18&lens=all&limit=2").second
        assertEquals(2, all.getValue("data").jsonArray.size); assertEquals("7", all.obj("pagination").str("total"))
        val zepto = get("/api/v1/coach/leaks/transactions?from=2026-10-12&to=2026-10-18&merchant=ZEPTO&nature=refund&category=shopping&q=zep").second
        assertEquals(listOf("100.00"), zepto.getValue("data").jsonArray.map { it.str("amount") })
    }

    @Test
    fun `bad parameters are 400s that name the parameter`() = routeTest {
        for ((q, name) in listOf(
            "from=2026-10-01" to "to", "from=2026-10-10&to=2026-10-01" to "to", "from=01-10-2026&to=2026-10-02" to "from",
            "from=2020-01-01&to=2026-10-01" to "from"
        )) {
            val (status, body) = get("/api/v1/coach/leaks?$q")
            assertEquals(HttpStatusCode.BadRequest, status, q)
            assertTrue(body.getValue("error").jsonObject.getValue("message").jsonPrimitive.content.contains(name), "$q → $body")
        }
        for ((q, name) in listOf("lens=gambling" to "lens", "lens=..%2F..%2Fetc" to "lens", "weekday=funday" to "weekday", "nature=income" to "nature",
                                 "nature=bogus" to "nature", "lateNight=maybe" to "lateNight", "category=rockets" to "category",
                                 "merchant=%20%20" to "merchant", "limit=5000" to "limit", "q=${"x".repeat(300)}" to "q")) {
            val (status, body) = get("/api/v1/coach/leaks/transactions?$q")
            assertEquals(HttpStatusCode.BadRequest, status, q)
            assertTrue(body.getValue("error").jsonObject.getValue("message").jsonPrimitive.content.contains(name), "$q → $body")
        }
    }

    @Test
    fun `hostile search text is only a filter`() = routeTest {
        for (hostile in listOf("'; DROP TABLE transactions; --", "<script>alert(1)</script>", "%", "_", "\u202E")) {
            val (status, body) = get("/api/v1/coach/leaks/transactions?q=${URLEncoder.encode(hostile, Charsets.UTF_8)}")
            assertEquals(HttpStatusCode.OK, status, hostile)
            assertEquals(0, body.getValue("data").jsonArray.size, hostile)
        }
    }

    @Test
    fun `another user sees none of this user's leaks`() = routeTest {
        val d = get("/api/v1/coach/leaks?from=2026-10-12&to=2026-10-18", who = other).second.data()
        assertEquals("0.00", d.obj("total").str("spent"))
        val list = get("/api/v1/coach/leaks/transactions?from=2026-10-12&to=2026-10-18", who = other).second
        assertEquals(0, list.getValue("data").jsonArray.size)
    }

    @Test
    fun `requires a signed-in user and refuses a forged token`() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/coach/leaks").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/coach/leaks/transactions").status)
        val forged = JwtService(AppConfig.load().auth.copy(jwtSecret = "a-different-signing-key-used-only-by-this-test-000000")).generateAccessToken(user)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/coach/leaks") { cookie(ACCESS_TOKEN_COOKIE, forged) }.status)
    }
}
