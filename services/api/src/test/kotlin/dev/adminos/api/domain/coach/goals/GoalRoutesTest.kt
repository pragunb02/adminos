package dev.adminos.api.domain.coach.goals

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.coach.CoachKit
import dev.adminos.api.domain.coach.CoachRoutes.asTheApp
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
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** /api/v1/coach/goals contract (AOS-907): create, list, delete; 400/404/409; auth; isolation. */
class GoalRoutesTest {
    private val jwtService = JwtService(AppConfig.load().auth)
    private val kit = CoachKit()
    private val owner = kit.owner
    private val other = User(id = UUID.randomUUID(), email = "o@example.com", googleId = "g-2", role = UserRole.OWNER, timezone = "Asia/Kolkata")

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureRequestBodyLimit(); configureAuthentication(jwtService)
            routing { route("/api/v1") { goalRoutes(kit.goals) } }
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
    private fun JsonObject.message() = getValue("error").jsonObject.getValue("message").jsonPrimitive.content

    @Test
    fun `create, list and delete a cap goal`() = routeTest {
        val (status, body) = call(HttpMethod.Post, "/api/v1/coach/goals", """{"lens":"quick_commerce","kind":"cap_amount","value":"1500"}""")
        assertEquals(HttpStatusCode.Created, status)
        val d = body.getValue("data").jsonObject
        assertEquals("1500.00", d.getValue("target").jsonPrimitive.content)
        assertEquals("weekly", d.getValue("period").jsonPrimitive.content)
        assertEquals(1, d.getValue("periods").jsonArray.size)
        val id = d.getValue("id").jsonPrimitive.content
        val list = call(HttpMethod.Get, "/api/v1/coach/goals").second.getValue("data").jsonArray
        assertEquals("Quick commerce", list.single().jsonObject.getValue("label").jsonPrimitive.content)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, "/api/v1/coach/goals/$id", who = other).first)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Delete, "/api/v1/coach/goals/$id").first)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, "/api/v1/coach/goals/$id").first)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, "/api/v1/coach/goals/not-a-uuid").first)
    }

    @Test
    fun `a cut with nothing to cut from, a duplicate and an unknown lens`() = routeTest {
        val (cut, c) = call(HttpMethod.Post, "/api/v1/coach/goals", """{"lens":"food_delivery","kind":"cut_percent","value":"30"}""")
        assertEquals(HttpStatusCode.Conflict, cut); assertTrue(c.message().contains("set a cap instead"))
        call(HttpMethod.Post, "/api/v1/coach/goals", """{"lens":"travel","kind":"cap_amount","value":"5000","period":"monthly"}""")
        assertEquals(HttpStatusCode.Conflict, call(HttpMethod.Post, "/api/v1/coach/goals", """{"lens":"travel","kind":"cap_amount","value":"1"}""").first)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/api/v1/coach/goals", """{"lens":"gambling","kind":"cap_amount","value":"1"}""").first)
        assertEquals(0, call(HttpMethod.Get, "/api/v1/coach/goals", who = other).second.getValue("data").jsonArray.size)
    }

    @Test
    fun `bad input is a 400 naming the field`() = routeTest {
        for ((body, name) in listOf(
            """{"lens":"travel","kind":"halve","value":"1"}""" to "kind",
            """{"lens":"travel","kind":"cap_amount","value":"1","period":"daily"}""" to "period",
            """{"lens":"travel","kind":"cap_amount","value":"-1"}""" to "value",
            """{"lens":"travel","kind":"cap_amount","value":"1e5"}""" to "value",
            """{"lens":"travel","kind":"cap_amount","value":"0"}""" to "value",
            """{"lens":"travel","kind":"cut_percent","value":"95"}""" to "value"
        )) {
            val (status, b) = call(HttpMethod.Post, "/api/v1/coach/goals", body)
            assertEquals(HttpStatusCode.BadRequest, status, body)
            assertTrue(b.message().contains(name), "$body → $b")
        }
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/api/v1/coach/goals", "<script>").first)
    }

    @Test
    fun `needs a session`() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/coach/goals").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/coach/goals") { contentType(ContentType.Application.Json); setBody("""{"lens":"travel","kind":"cap_amount","value":"1"}""") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/v1/coach/goals/${UUID.randomUUID()}").status)
    }
}
