package dev.adminos.api.domain.ingestion.cash

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.financial.CategoryRule
import dev.adminos.api.domain.financial.InMemoryCategoryRuleRepository
import dev.adminos.api.domain.financial.RuleMatch
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
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
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** POST /api/v1/transactions/cash/parse (AOS-718), and the cash entry's `date` field. */
class CashPhraseRoutesTest {
    private val jwtService = JwtService(AppConfig.load().auth)
    private val me = User(id = UUID.randomUUID(), email = "me@example.com", googleId = "g-718", timezone = "Asia/Kolkata")
    private val someoneElse = User(id = UUID.randomUUID(), email = "other@example.com", googleId = "g-719", timezone = "Asia/Kolkata")
    private val rules = InMemoryCategoryRuleRepository()
    private val events = InMemoryIngestEventRepository()
    /** 17 Oct 2026, 23:00 in India (17:30 UTC): still the 17th for the user, whatever the server's zone. */
    private val clock = Clock.fixed(Instant.parse("2026-10-17T17:30:00Z"), ZoneOffset.UTC)
    private val limiter = RateLimiter(maxRequests = 5, windowSeconds = 60)

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            routing {
                route("/api/v1") {
                    cashPhraseRoutes(RuleCashPhraseParser(CashCategoryGuesser(rules)), clock, limiter)
                    cashRoutes(CashIntake(IngestEventService(events), clock), clock)
                }
            }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.post(path: String, body: String, user: User? = me, type: ContentType = ContentType.Application.Json): Pair<HttpStatusCode, JsonObject?> {
        val res = client.post("/api/v1$path") {
            if (user != null) cookie(ACCESS_TOKEN_COOKIE, jwtService.generateAccessToken(user))
            contentType(type); setBody(body)
        }
        val text = res.bodyAsText()
        return res.status to text.takeIf { it.startsWith("{") }?.let { Json.parseToJsonElement(it).jsonObject }
    }

    private fun JsonObject.data() = this["data"]!!.jsonObject

    @Test
    fun `a phrase becomes entries to preview, on the user's own date, and nothing is saved`() = routeTest {
        val (status, body) = post("/transactions/cash/parse", """{"text":"200 chai and 150 auto yesterday, xyz"}""")
        assertEquals(HttpStatusCode.OK, status)
        val data = body!!.data()
        assertEquals("2026-10-17", data["today"]!!.jsonPrimitive.content)
        val entries = data["entries"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("200.00", "150.00"), entries.map { it["amount"]!!.jsonPrimitive.content })
        assertEquals(listOf("food", "transport"), entries.map { it["category"]!!.jsonPrimitive.content })
        assertEquals(listOf("2026-10-16", "2026-10-16"), entries.map { it["date"]!!.jsonPrimitive.content })
        assertEquals("keyword", entries[0]["categoryFrom"]!!.jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), entries[0]["warnings"])
        val unparsed = data["unparsed"]!!.jsonArray.single().jsonObject
        assertEquals("no_amount", unparsed["reason"]!!.jsonPrimitive.content)
        assertTrue(runBlocking { events.findByUser(me.id) }.isEmpty(), "a preview saves nothing")
    }

    @Test
    fun `warnings are listed in a stable order`() = routeTest {
        val (_, body) = post("/transactions/cash/parse", """{"text":"kal 300"}""")
        val warnings = body!!.data()["entries"]!!.jsonArray.single().jsonObject["warnings"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("category_unknown", "date_assumed", "no_note"), warnings)
    }

    @Test
    fun `one user's corrections never shape another user's preview`() = routeTest {
        runBlocking { rules.upsert(CategoryRule(userId = someoneElse.id, match = RuleMatch.MERCHANT, value = "raju", category = TransactionCategory.HEALTH)) }
        val (_, mine) = post("/transactions/cash/parse", """{"text":"raju 500"}""")
        assertEquals("other", mine!!.data()["entries"]!!.jsonArray.single().jsonObject["category"]!!.jsonPrimitive.content)
        val (_, theirs) = post("/transactions/cash/parse", """{"text":"raju 500"}""", user = someoneElse)
        assertEquals("health", theirs!!.data()["entries"]!!.jsonArray.single().jsonObject["category"]!!.jsonPrimitive.content)
    }

    @Test
    fun `blank, oversized and malformed bodies are 400s`() = routeTest {
        assertEquals(HttpStatusCode.BadRequest, post("/transactions/cash/parse", """{"text":"   "}""").first)
        val (status, body) = post("/transactions/cash/parse", """{"text":"${"a".repeat(501)}"}""")
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body!!["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.startsWith("text:"))
        assertEquals(HttpStatusCode.BadRequest, post("/transactions/cash/parse", """{"text":42}""").first)
        assertEquals(HttpStatusCode.BadRequest, post("/transactions/cash/parse", """{"txt":"chai 40"}""").first)
        assertEquals(HttpStatusCode.BadRequest, post("/transactions/cash/parse", "not json").first)
    }

    @Test
    fun `a cross-site form post is refused, and signed-out or forged sessions get 401`() = routeTest {
        // A form or text/plain body is what another site could send without a preflight; only JSON is read.
        val (form, _) = post("/transactions/cash/parse", "text=chai+40", type = ContentType.Application.FormUrlEncoded)
        assertEquals(HttpStatusCode.UnsupportedMediaType, form)
        val (plain, _) = post("/transactions/cash/parse", """{"text":"chai 40"}""", type = ContentType.Text.Plain)
        assertEquals(HttpStatusCode.UnsupportedMediaType, plain)
        assertEquals(HttpStatusCode.Unauthorized, post("/transactions/cash/parse", """{"text":"chai 40"}""", user = null).first)
        val forged = client.post("/api/v1/transactions/cash/parse") {
            cookie(ACCESS_TOKEN_COOKIE, jwtService.generateAccessToken(me).dropLast(3) + "abc")
            contentType(ContentType.Application.Json); setBody("""{"text":"chai 40"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, forged.status)
    }

    @Test
    fun `previews are rate limited per user`() = routeTest {
        repeat(5) { assertEquals(HttpStatusCode.OK, post("/transactions/cash/parse", """{"text":"chai 40"}""").first) }
        val (status, body) = post("/transactions/cash/parse", """{"text":"chai 40"}""")
        assertEquals(HttpStatusCode.TooManyRequests, status)
        assertEquals("RATE_001", body!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, post("/transactions/cash/parse", """{"text":"chai 40"}""", user = someoneElse).first)
    }

    // ── Saving a previewed entry: the date field ──

    @Test
    fun `a previewed entry is saved on its day, today as now and earlier days at noon`() = routeTest {
        val today = """{"clientRef":"${UUID.randomUUID()}","amount":"40","category":"food","note":"Chai","date":"2026-10-17"}"""
        assertEquals(HttpStatusCode.Accepted, post("/transactions/cash", today).first)
        val past = """{"clientRef":"${UUID.randomUUID()}","amount":"150","category":"transport","note":"Auto","date":"2026-10-16"}"""
        assertEquals(HttpStatusCode.Accepted, post("/transactions/cash", past).first)
        val saved = runBlocking { events.findByUser(me.id) }.associateBy { it.merchantRaw }
        assertEquals(clock.instant(), saved.getValue("Chai").occurredAt)
        assertEquals(Instant.parse("2026-10-16T06:30:00Z"), saved.getValue("Auto").occurredAt)
    }

    @Test
    fun `a future, malformed or doubled date is a 400 naming the field`() = routeTest {
        val r = UUID.randomUUID()
        for (body in listOf(
            """{"clientRef":"$r","amount":"40","category":"food","date":"2026-10-18"}""",
            """{"clientRef":"$r","amount":"40","category":"food","date":"yesterday"}""",
            """{"clientRef":"$r","amount":"40","category":"food","date":"2026-10-16","occurredAt":"2026-10-16T09:00:00+05:30"}""",
        )) {
            val (status, json) = post("/transactions/cash", body)
            assertEquals(HttpStatusCode.BadRequest, status, body)
            assertTrue(json!!["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.startsWith("date:"), json.toString())
        }
    }
}
