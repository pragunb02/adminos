package dev.adminos.api.domain.analytics

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.analytics.AnalyticsFixture.IST
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
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** /api/v1/analytics contract (AOS-301): exact decimal strings, camelCase, defaults, 400s, auth. */
class AnalyticsRoutesTest {
    private val jwtService = JwtService(AppConfig.load().auth)
    private val user = User(id = UUID.randomUUID(), email = "me@example.com", googleId = "g-1", role = UserRole.OWNER)
    private val jwt = jwtService.generateAccessToken(user)
    private val txns = InMemoryTransactionRepository().also { r -> runBlocking { AnalyticsFixture.all(user.id).forEach { r.save(it) } } }
    private val clock = Clock.fixed(LocalDate.of(2026, 10, 16).atTime(12, 0).atZone(IST).toInstant(), IST)

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            routing { route("/api/v1") { analyticsRoutes(AnalyticsService(InMemoryAnalyticsRepository(txns), clock)) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.get(path: String): Pair<HttpStatusCode, JsonObject> {
        val res = client.get(path) { cookie(ACCESS_TOKEN_COOKIE, jwt) }
        return res.status to Json.parseToJsonElement(res.bodyAsText()).jsonObject
    }
    private fun JsonObject.data() = this["data"]!!.jsonObject
    private fun JsonElement.str(key: String) = jsonObject[key]!!.jsonPrimitive.content

    @Test
    fun `summary defaults to this month and returns exact strings`() = routeTest {
        val (status, body) = get("/api/v1/analytics/summary")
        assertEquals(HttpStatusCode.OK, status)
        val d = body.data()
        assertEquals("2026-10-01", d["period"]!!.str("from")); assertEquals("2026-10-16", d["period"]!!.str("to"))
        assertEquals("1249.00", d["totals"]!!.str("spent")); assertEquals("100000.00", d["totals"]!!.str("received"))
        assertTrue(d["totals"]!!.jsonObject["spent"]!!.jsonPrimitive.isString)
        assertEquals("1000.00", d["previous"]!!.str("spent")); assertEquals("24.9", d["spentChangePct"]!!.jsonPrimitive.content)
    }

    @Test
    fun `series, categories and merchants`() = routeTest {
        val week = get("/api/v1/analytics/series?from=2026-10-01&to=2026-10-16&bucket=week").second.data()
        assertEquals("week", week["bucket"]!!.jsonPrimitive.content)
        assertEquals(listOf("2026-09-28", "2026-10-05", "2026-10-12"), week["points"]!!.jsonArray.map { it.str("start") })
        val cats = get("/api/v1/analytics/categories?from=2026-10-01&to=2026-10-16").second.data()["categories"]!!.jsonArray
        assertEquals("shopping", cats[0].str("category")); assertEquals("699.00", cats[0].str("spent")); assertEquals("1000.00", cats[1].str("previousSpent"))
        val top = get("/api/v1/analytics/merchants?from=2026-10-01&to=2026-10-16&limit=2").second.data()["merchants"]!!.jsonArray
        assertEquals(listOf("Amazon", "Swiggy"), top.map { it.str("merchant") })
    }

    @Test
    fun `bad parameters are 400s that name the parameter`() = routeTest {
        for ((q, name) in listOf("from=2026-10-01" to "to", "from=2026-10-10&to=2026-10-01" to "to", "from=01-10-2026&to=2026-10-02" to "from",
                                 "bucket=hour" to "bucket")) {
            val (status, body) = get("/api/v1/analytics/series?$q")
            assertEquals(HttpStatusCode.BadRequest, status, q)
            assertTrue(body["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains(name), "$q → $body")
        }
    }

    @Test
    fun `requires a signed-in user`() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/analytics/summary").status)
    }

    @Test
    fun `series and merchants take an optional category, and reject unknown ones (AOS-305)`() = routeTest {
        val shopping = get("/api/v1/analytics/series?from=2026-10-01&to=2026-10-16&bucket=month&category=shopping").second.data()
        assertEquals("699.00", shopping["points"]!!.jsonArray.single().str("spent"))
        val food = get("/api/v1/analytics/merchants?from=2026-10-01&to=2026-10-16&category=food").second.data()["merchants"]!!.jsonArray
        assertEquals(listOf("Swiggy", "Unknown"), food.map { it.str("merchant") })
        val (status, body) = get("/api/v1/analytics/merchants?category=groceries")
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("category"))
    }
}
