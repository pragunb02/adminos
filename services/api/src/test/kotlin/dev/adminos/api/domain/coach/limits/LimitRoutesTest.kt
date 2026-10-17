package dev.adminos.api.domain.coach.limits

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.coach.CoachFixture.txn
import dev.adminos.api.domain.coach.CoachKit
import dev.adminos.api.domain.coach.CoachRoutes.asTheApp
import dev.adminos.api.domain.coach.settings.coachSettingsRoutes
import dev.adminos.api.domain.financial.TransactionCategory
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
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** /api/v1/coach/limits and /coach/settings contract (AOS-904): CRUD, learning 409, ownership 404, validation, auth. */
class LimitRoutesTest {
    private val jwtService = JwtService(AppConfig.load().auth)
    private val kit = CoachKit().also { k -> runBlocking { k.history(LocalDateTime.of(2026, 10, 1, 9, 0)) } }
    private val owner = kit.owner
    private val other = User(id = UUID.randomUUID(), email = "other@example.com", googleId = "g-2", role = UserRole.OWNER, timezone = "Asia/Kolkata")

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureRequestBodyLimit(); configureAuthentication(jwtService)
            routing { route("/api/v1") { limitRoutes(kit.limits); coachSettingsRoutes(kit.settings) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, path: String, body: String? = null, who: User = owner): Pair<HttpStatusCode, JsonObject> {
        val res = client.request(path) {
            this.method = method
            cookie(ACCESS_TOKEN_COOKIE, jwtService.generateAccessToken(who))
            asTheApp()
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        return res.status to Json.parseToJsonElement(res.bodyAsText()).jsonObject
    }
    private fun JsonObject.data() = getValue("data").jsonObject
    private fun JsonElement.str(key: String) = jsonObject.getValue(key).jsonPrimitive.content
    private fun JsonObject.message() = getValue("error").jsonObject.getValue("message").jsonPrimitive.content
    private val qcBody = """{"scope":{"kind":"lens","value":"quick_commerce"},"period":"weekly","amount":"1000"}"""

    @Test
    fun `create, read, change and delete a limit`() = routeTest {
        kit.transactions.save(txn("Zepto", "600.00", LocalDateTime.of(2026, 11, 17, 12, 0), category = TransactionCategory.SHOPPING, user = owner.id))
        val (created, c) = call(HttpMethod.Post, "/api/v1/coach/limits", qcBody)
        assertEquals(HttpStatusCode.Created, created)
        val id = c.data().str("id")
        assertEquals("Quick commerce", c.data().str("label"))
        assertEquals("1000.00", c.data().str("amount"))
        val status = c.data().getValue("status").jsonObject
        assertEquals("600.00", status.str("spent")); assertEquals("0.6000", status.str("used")); assertEquals("2026-11-16", status.str("periodFrom"))
        assertEquals(listOf(50), status.getValue("reached").jsonArray.map { it.jsonPrimitive.int })

        val (_, list) = call(HttpMethod.Get, "/api/v1/coach/limits")
        assertEquals("true", list.data().getValue("learning").str("ready"))
        assertEquals(1, list.data().getValue("limits").jsonArray.size)

        val (patched, p) = call(HttpMethod.Patch, "/api/v1/coach/limits/$id", """{"amount":"2000.50","period":"monthly"}""")
        assertEquals(HttpStatusCode.OK, patched)
        assertEquals("2000.50", p.data().str("amount")); assertEquals("monthly", p.data().str("period"))
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Delete, "/api/v1/coach/limits/$id").first)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, "/api/v1/coach/limits/$id").first)
    }

    @Test
    fun `merchant and category scopes, duplicates and dismissals`() = routeTest {
        assertEquals(HttpStatusCode.Created, call(HttpMethod.Post, "/api/v1/coach/limits", """{"scope":{"kind":"merchant","value":" Zepto "},"period":"weekly","amount":"500"}""").first)
        assertEquals(HttpStatusCode.Created, call(HttpMethod.Post, "/api/v1/coach/limits", """{"scope":{"kind":"category","value":"Food"},"period":"monthly","amount":"8000"}""").first)
        call(HttpMethod.Post, "/api/v1/coach/limits", qcBody)
        val (dup, d) = call(HttpMethod.Post, "/api/v1/coach/limits", qcBody)
        assertEquals(HttpStatusCode.Conflict, dup); assertTrue(d.message().contains("already"))
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/api/v1/coach/limits/suggestions/travel/dismiss").first)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/api/v1/coach/limits/suggestions/gambling/dismiss").first)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/api/v1/coach/limits", """{"scope":{"kind":"lens","value":"gambling"},"period":"weekly","amount":"5"}""").first)
    }

    @Test
    fun `during the learning month a limit is a 409 that says how far along`() = routeTest {
        val fresh = User(id = UUID.randomUUID(), email = "new@example.com", googleId = "g-3", role = UserRole.OWNER, timezone = "Asia/Kolkata")
        val (status, body) = call(HttpMethod.Post, "/api/v1/coach/limits", qcBody, who = fresh)
        assertEquals(HttpStatusCode.Conflict, status)
        assertTrue(body.message().startsWith("Learning your habits: 0 of 30 days"), body.toString())
        assertEquals("false", call(HttpMethod.Get, "/api/v1/coach/limits", who = fresh).second.data().getValue("learning").str("ready"))
    }

    @Test
    fun `another user's limit is not found`() = routeTest {
        val id = call(HttpMethod.Post, "/api/v1/coach/limits", qcBody).second.data().str("id")
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Patch, "/api/v1/coach/limits/$id", """{"amount":"1"}""", who = other).first)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, "/api/v1/coach/limits/$id", who = other).first)
        assertEquals(0, call(HttpMethod.Get, "/api/v1/coach/limits", who = other).second.data().getValue("limits").jsonArray.size)
        assertEquals(1, call(HttpMethod.Get, "/api/v1/coach/limits").second.data().getValue("limits").jsonArray.size)
    }

    @Test
    fun `bad input is a 400 naming the field`() = routeTest {
        for ((body, name) in listOf(
            """{"scope":{"kind":"lens","value":"quick_commerce"},"period":"daily","amount":"1000"}""" to "period",
            """{"scope":{"kind":"lens","value":"quick_commerce"},"period":"weekly","amount":"-5"}""" to "amount",
            """{"scope":{"kind":"lens","value":"quick_commerce"},"period":"weekly","amount":"1e9"}""" to "amount",
            """{"scope":{"kind":"lens","value":"quick_commerce"},"period":"weekly","amount":"0"}""" to "amount",
            """{"scope":{"kind":"lens","value":"quick_commerce"},"period":"weekly","amount":"99999999.999"}""" to "amount",
            """{"scope":{"kind":"wallet","value":"x"},"period":"weekly","amount":"10"}""" to "scope.kind",
            """{"scope":{"kind":"category","value":"transfer"},"period":"weekly","amount":"10"}""" to "transfers",
            """{"scope":{"kind":"category","value":"rockets"},"period":"weekly","amount":"10"}""" to "scope.value",
            """{"scope":{"kind":"lens","value":"../../etc"},"period":"weekly","amount":"10"}""" to "scope.value",
            """{"scope":{"kind":"merchant","value":"  "},"period":"weekly","amount":"10"}""" to "merchant"
        )) {
            val (status, b) = call(HttpMethod.Post, "/api/v1/coach/limits", body)
            assertEquals(HttpStatusCode.BadRequest, status, body)
            assertTrue(b.message().contains(name), "$body → $b")
        }
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/api/v1/coach/limits", "not json").first)
        val id = call(HttpMethod.Post, "/api/v1/coach/limits", qcBody).second.data().str("id")
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Patch, "/api/v1/coach/limits/$id", "{}").first)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Patch, "/api/v1/coach/limits/not-a-uuid", """{"amount":"1"}""").first)
    }

    @Test
    fun `hostile merchant text is stored as text`() = routeTest {
        val hostile = "<script>alert(1)</script>'; DROP TABLE spending_limits; --"
        val body = buildJsonObject { putJsonObject("scope") { put("kind", "merchant"); put("value", hostile) }; put("period", "weekly"); put("amount", "10") }.toString()
        val (status, b) = call(HttpMethod.Post, "/api/v1/coach/limits", body)
        assertEquals(HttpStatusCode.Created, status)
        assertEquals(hostile, b.data().str("label"))
    }

    @Test
    fun `coach settings read with defaults and change`() = routeTest {
        assertEquals("true", call(HttpMethod.Get, "/api/v1/coach/settings").second.data().str("limitAlerts"))
        assertEquals("false", call(HttpMethod.Patch, "/api/v1/coach/settings", """{"limitAlerts":false}""").second.data().str("limitAlerts"))
        assertEquals("false", call(HttpMethod.Patch, "/api/v1/coach/settings", "{}").second.data().str("limitAlerts"))
        assertEquals("true", call(HttpMethod.Get, "/api/v1/coach/settings", who = other).second.data().str("limitAlerts"))
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Patch, "/api/v1/coach/settings", """{"limitAlerts":"maybe"}""").first)
    }

    @Test
    fun `every route needs a session and refuses a forged one`() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/coach/limits").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/coach/limits") { contentType(ContentType.Application.Json); setBody(qcBody) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.patch("/api/v1/coach/settings") { contentType(ContentType.Application.Json); setBody("{}") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/v1/coach/limits/${UUID.randomUUID()}").status)
        val forged = JwtService(AppConfig.load().auth.copy(jwtSecret = "a-different-signing-key-used-only-by-this-test-000000")).generateAccessToken(owner)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/coach/limits") { cookie(ACCESS_TOKEN_COOKIE, forged) }.status)
        assertEquals(0, kit.limitRepo.findByUser(owner.id).size)
    }
}
