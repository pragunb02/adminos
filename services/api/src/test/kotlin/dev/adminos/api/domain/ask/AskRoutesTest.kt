package dev.adminos.api.domain.ask

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.ask.AskFixture.Companion.answer
import dev.adminos.api.domain.ask.AskFixture.Companion.call
import dev.adminos.api.domain.ask.AskFixture.Companion.completed
import dev.adminos.api.domain.ask.AskFixture.Companion.period
import dev.adminos.api.domain.ask.AskFixture.Companion.tools
import dev.adminos.api.domain.ask.eval.AskSeed
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.domain.scheduling.TestClock
import dev.adminos.api.infrastructure.plugins.ACCESS_TOKEN_COOKIE
import dev.adminos.api.infrastructure.plugins.configureAuthentication
import dev.adminos.api.infrastructure.plugins.configureErrorHandling
import dev.adminos.api.infrastructure.plugins.configureRequestId
import dev.adminos.api.infrastructure.plugins.configureSerialization
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `/api/v1/ask` (AOS-710): auth, validation, limits, scoping by token, and the response contract. */
class AskRoutesTest {
    private val jwtService = JwtService(AppConfig.load().auth)
    private val owner = User(id = AskSeed.OWNER, email = "owner@example.com", googleId = "g-712", role = UserRole.OWNER)
    private val stranger = User(id = AskSeed.STRANGER, email = "other@example.com", googleId = "g-713", role = UserRole.OWNER)
    private val ownerJwt = jwtService.generateAccessToken(owner)

    private fun routeTest(f: AskFixture, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            routing { route("/api/v1") { askRoutes(f.service) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.ask(body: String, jwt: String? = ownerJwt): HttpResponse = client.post("/api/v1/ask") {
        jwt?.let { cookie(ACCESS_TOKEN_COOKIE, it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.errorCode() = json()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    private val zepto = tools(call("merchant_spend", merchant = "Zepto"), call("merchant_spend", period("this_month"), merchant = "Zepto"))

    @Test
    fun `signed out, a forged token or an expired-looking token gets 401 and no model call`() {
        val f = AskFixture()
        routeTest(f) {
            assertEquals(HttpStatusCode.Unauthorized, ask("""{"question":"zepto"}""", jwt = null).status)
            assertEquals(HttpStatusCode.Unauthorized, ask("""{"question":"zepto"}""", jwt = ownerJwt.dropLast(4) + "AAAA").status)
            assertEquals(HttpStatusCode.Unauthorized, ask("""{"question":"zepto"}""", jwt = "not.a.jwt").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/ask").status)
        }
        assertEquals(0, f.provider.requests.size)
    }

    @Test
    fun `an answer comes back with its steps, exact amounts and comparisons by step id`() {
        val f = AskFixture(plan = { completed(zepto) }, answer = { answer("₹3,792 last month, ₹1,440 this month.") })
        routeTest(f) {
            val res = ask("""{"question":"Zepto last month vs this month"}""")
            assertEquals(HttpStatusCode.OK, res.status)
            val data = res.json()["data"]!!.jsonObject
            assertEquals("model", data["source"]!!.jsonPrimitive.content)
            assertEquals("₹3,792 last month, ₹1,440 this month.", data["answer"]!!.jsonPrimitive.content)
            val steps = data["steps"]!!.jsonArray
            assertEquals(listOf("t1", "t2"), steps.map { it.jsonObject["id"]!!.jsonPrimitive.content })
            val first = steps[0].jsonObject
            assertEquals("merchant_spend", first["tool"]!!.jsonPrimitive.content)
            assertEquals("Zepto", first["merchant"]!!.jsonPrimitive.content)
            assertEquals("""{"from":"2026-09-01","to":"2026-09-30"}""", first["period"].toString())
            assertEquals("3792.00", first["result"]!!.jsonObject["total"]!!.jsonPrimitive.content)
            val comparison = data["comparisons"]!!.jsonArray.single().jsonObject
            assertEquals("t2", comparison["later"]!!.jsonPrimitive.content)
            assertEquals("-2352.00", comparison["difference"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `the user comes from the token, so another user's token sees only their own money`() {
        val f = AskFixture(plan = { completed(tools(call("merchant_spend", period("today"), merchant = "Zepto"))) }, answer = { answer("₹77,777 today.") })
        routeTest(f) {
            val mine = ask("""{"question":"zepto today","userId":"${AskSeed.STRANGER}"}""").json()["data"]!!.jsonObject
            assertEquals("0.00", mine["steps"]!!.jsonArray.single().jsonObject["result"]!!.jsonObject["total"]!!.jsonPrimitive.content)
            assertEquals("template", mine["source"]!!.jsonPrimitive.content, "₹77,777 isn't in the owner's results")
            val theirs = ask("""{"question":"zepto today"}""", jwtService.generateAccessToken(stranger)).json()["data"]!!.jsonObject
            assertEquals("77777.00", theirs["steps"]!!.jsonArray.single().jsonObject["result"]!!.jsonObject["total"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `bad bodies are 400 with a code and never call a model`() {
        val f = AskFixture()
        routeTest(f) {
            assertEquals("ASK_001", ask("""{"question":"  "}""").also { assertEquals(HttpStatusCode.BadRequest, it.status) }.errorCode())
            assertEquals("ASK_002", ask("""{"question":"${"a".repeat(301)}"}""").errorCode())
            assertEquals("ASK_003", ask("""{"question":"zepto\u202E"}""").errorCode())
            assertEquals(HttpStatusCode.BadRequest, ask("""{"question":42}""").status)
            assertEquals(HttpStatusCode.BadRequest, ask("""{}""").status)
            assertEquals(HttpStatusCode.BadRequest, ask("""not json""").status)
        }
        assertEquals(0, f.provider.requests.size)
    }

    @Test
    fun `Ask off is 409 and the status says so`() {
        routeTest(AskFixture(enabled = false)) {
            val res = ask("""{"question":"zepto"}""")
            assertEquals(HttpStatusCode.Conflict, res.status)
            assertEquals("ASK_004", res.errorCode())
            val status = client.get("/api/v1/ask") { cookie(ACCESS_TOKEN_COOKIE, ownerJwt) }.json()["data"]!!.jsonObject
            assertEquals("false", status["enabled"]!!.jsonPrimitive.content)
            assertEquals("300", status["maxQuestionLength"]!!.jsonPrimitive.content)
        }
        routeTest(AskFixture(consent = { false })) { assertEquals("ASK_005", ask("""{"question":"zepto"}""").errorCode()) }
    }

    @Test
    fun `the rate limit is 429 with Retry-After`() {
        val clock = TestClock(AskSeed.CLOCK.instant())
        routeTest(AskFixture(quota = AskQuota(clock, perHour = 1, perDay = 5))) {
            assertEquals(HttpStatusCode.OK, ask("""{"question":"one"}""").status)
            val limited = ask("""{"question":"two"}""")
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("3600", limited.headers["Retry-After"])
            assertEquals("RATE_001", limited.errorCode())
        }
    }

    @Test
    fun `no budget or no model is 503 without leaking anything`() {
        routeTest(AskFixture(capUsd = "0")) {
            val res = ask("""{"question":"zepto 98000 00012"}""")
            assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
            assertEquals("ASK_006", res.errorCode())
            assertTrue("98000" !in res.bodyAsText())
        }
        routeTest(AskFixture(plan = { dev.adminos.api.domain.ai.AiCompletion.Failed(dev.adminos.api.domain.ai.AiReasonCode("network")) })) {
            assertEquals("ASK_007", ask("""{"question":"zepto"}""").errorCode())
        }
    }

    @Test
    fun `every kind of result serializes with exact amounts`() {
        val plan = tools(call("spending_summary", period("today")), call("category_spend", period("today")), call("spending_trend", period("last_week"), bucket = "day", category = "food"))
        routeTest(AskFixture(plan = { completed(plan) }, answer = { answer("₹103 today.") })) {
            val steps = ask("""{"question":"today and last week"}""").json()["data"]!!.jsonObject["steps"]!!.jsonArray.map { it.jsonObject }
            val summary = steps[0]["result"]!!.jsonObject
            assertEquals("103.00", summary["spent"]!!.jsonPrimitive.content)
            assertEquals("0.00", summary["received"]!!.jsonPrimitive.content)
            assertEquals("1", summary["payments"]!!.jsonPrimitive.content)
            val categories = steps[1]["result"]!!.jsonObject
            assertEquals("null", categories["spent"].toString())
            assertTrue(categories["rows"]!!.jsonArray.isNotEmpty())
            assertEquals("null", categories["rows"]!!.jsonArray[0].jsonObject["category"].toString())
            val trend = steps[2]
            assertEquals("day", trend["bucket"]!!.jsonPrimitive.content)
            assertEquals("food", trend["category"]!!.jsonPrimitive.content)
            assertEquals(7, trend["result"]!!.jsonObject["points"]!!.jsonArray.size)
        }
        routeTest(AskFixture(plan = { completed(tools(call("top_merchants", period("this_month"), limit = 2))) }, answer = { answer("Amazon led.") })) {
            val step = ask("""{"question":"top 2 this month"}""").json()["data"]!!.jsonObject["steps"]!!.jsonArray.single().jsonObject
            val rows = step["result"]!!.jsonObject["rows"]!!.jsonArray
            assertEquals(listOf("Amazon", "Flipkart"), rows.map { it.jsonObject["label"]!!.jsonPrimitive.content })
            assertEquals("shopping", rows[0].jsonObject["category"]!!.jsonPrimitive.content)
            assertEquals("null", step["result"]!!.jsonObject["total"].toString())
        }
    }

    @Test
    fun `a decline and a refused step serialize their reasons`() {
        routeTest(AskFixture(plan = { completed("""{"action":"decline","reason":"future","calls":[]}""") })) {
            val data = ask("""{"question":"next month?"}""").json()["data"]!!.jsonObject
            assertEquals("declined", data["source"]!!.jsonPrimitive.content)
            assertEquals("future", data["declineReason"]!!.jsonPrimitive.content)
        }
        routeTest(AskFixture(plan = { completed(tools(call("top_merchants", """{"preset":"month","month":"2026-12","from":null,"to":null}""", category = "food", limit = 3))) })) {
            val step = ask("""{"question":"december food"}""").json()["data"]!!.jsonObject["steps"]!!.jsonArray.single().jsonObject
            assertEquals("future_period", step["skipped"]!!.jsonPrimitive.content)
            assertEquals("food", step["category"]!!.jsonPrimitive.content)
            assertEquals("3", step["limit"]!!.jsonPrimitive.content)
            assertEquals("null", step["period"].toString())
        }
    }
}
