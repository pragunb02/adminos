package dev.adminos.api.domain.coach.lenses

import dev.adminos.api.config.AppConfig
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
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** /api/v1/coach/lenses contract (AOS-901): list, add, remove, reset; 401, 404, 400. */
class LensRoutesTest {
    private val jwtService = JwtService(AppConfig.load().auth)
    private val user = User(id = UUID.randomUUID(), email = "me@example.com", googleId = "g-1", role = UserRole.OWNER)
    private val other = User(id = UUID.randomUUID(), email = "other@example.com", googleId = "g-2", role = UserRole.OWNER)
    private val repo = InMemoryLensOverrideRepository()
    private val service = LensService(LensMatcher(), repo, Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC))

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            routing { route("/api/v1") { lensRoutes(service) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, path: String, body: String? = null, who: User = user): Pair<HttpStatusCode, JsonObject> {
        val res = client.request(path) {
            this.method = method
            cookie(ACCESS_TOKEN_COOKIE, jwtService.generateAccessToken(who))
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        return res.status to Json.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    private fun JsonObject.data() = getValue("data").jsonObject
    private fun JsonElement.str(key: String) = jsonObject.getValue(key).jsonPrimitive.content
    private fun JsonObject.lens(id: String) = data().getValue("lenses").jsonArray.first { it.str("id") == id }.jsonObject

    @Test
    fun `lists the six lenses with their rules and the late-night window`() = routeTest {
        val (status, body) = call(HttpMethod.Get, "/api/v1/coach/lenses")
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(6, body.data().getValue("lenses").jsonArray.size)
        assertEquals("22:00", body.data().getValue("lateNight").str("from"))
        val food = body.lens("food_delivery")
        assertEquals(listOf("food_delivery"), food.getValue("subcategories").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(food.getValue("brands").jsonArray.any { it.jsonPrimitive.content == "Swiggy" })
        assertTrue(body.lens("nights_out").getValue("keywords").jsonArray.any { it.jsonPrimitive.content == "pub" })
        assertEquals(0, food.getValue("added").jsonArray.size)
    }

    @Test
    fun `adding, removing and resetting a merchant`() = routeTest {
        val (added, a) = call(HttpMethod.Put, "/api/v1/coach/lenses/nights_out/merchants", """{"merchant":" Rohit Sharma ","include":true}""")
        assertEquals(HttpStatusCode.OK, added)
        assertEquals("Rohit Sharma", a.data().str("merchant"))
        call(HttpMethod.Put, "/api/v1/coach/lenses/shopping/merchants", """{"merchant":"Amazon","include":false}""")
        val (_, list) = call(HttpMethod.Get, "/api/v1/coach/lenses")
        assertEquals(listOf("Rohit Sharma"), list.lens("nights_out").getValue("added").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("Amazon"), list.lens("shopping").getValue("removed").jsonArray.map { it.jsonPrimitive.content })

        // Same merchant again replaces the choice.
        call(HttpMethod.Put, "/api/v1/coach/lenses/shopping/merchants", """{"merchant":"amazon","include":true}""")
        assertEquals(1, repo.findByUser(user.id).count { it.lensId == LensId("shopping") })

        val (reset, r) = call(HttpMethod.Delete, "/api/v1/coach/lenses/shopping/merchants?merchant=AMAZON")
        assertEquals(HttpStatusCode.OK, reset)
        assertEquals("true", r.data().str("cleared"))
        val (_, again) = call(HttpMethod.Delete, "/api/v1/coach/lenses/shopping/merchants?merchant=AMAZON")
        assertEquals("false", again.data().str("cleared"))
    }

    @Test
    fun `overrides are per user`() = routeTest {
        call(HttpMethod.Put, "/api/v1/coach/lenses/travel/merchants", """{"merchant":"Goa Bus","include":true}""")
        val (_, theirs) = call(HttpMethod.Get, "/api/v1/coach/lenses", who = other)
        assertEquals(0, theirs.lens("travel").getValue("added").jsonArray.size)
    }

    @Test
    fun `an unknown lens is a 404`() = routeTest {
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Put, "/api/v1/coach/lenses/gambling/merchants", """{"merchant":"X","include":true}""").first)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Put, "/api/v1/coach/lenses/Bad-Id/merchants", """{"merchant":"X","include":true}""").first)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, "/api/v1/coach/lenses/gambling/merchants?merchant=X").first)
    }

    @Test
    fun `a blank or too long merchant is a 400`() = routeTest {
        val (status, body) = call(HttpMethod.Put, "/api/v1/coach/lenses/travel/merchants", """{"merchant":"  ","include":true}""")
        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body.getValue("error").jsonObject.getValue("message").jsonPrimitive.content.contains("merchant"))
        val long = "x".repeat(256)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Put, "/api/v1/coach/lenses/travel/merchants", """{"merchant":"$long","include":true}""").first)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Delete, "/api/v1/coach/lenses/travel/merchants").first)
    }

    @Test
    fun `requires a signed-in user for reads and every mutation`() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/coach/lenses").status)
        val put = client.put("/api/v1/coach/lenses/travel/merchants") { contentType(ContentType.Application.Json); setBody("""{"merchant":"X","include":true}""") }
        assertEquals(HttpStatusCode.Unauthorized, put.status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/v1/coach/lenses/travel/merchants?merchant=X").status)
        assertEquals(0, repo.findByUser(user.id).size)
    }

    @Test
    fun `a forged or tampered token is refused`() = routeTest {
        val forged = JwtService(AppConfig.load().auth.copy(jwtSecret = "a-different-signing-key-used-only-by-this-test-000000")).generateAccessToken(user)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/coach/lenses") { cookie(ACCESS_TOKEN_COOKIE, forged) }.status)
        val tampered = jwtService.generateAccessToken(user).dropLast(3) + "abc"
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/coach/lenses") { bearerAuth(tampered) }.status)
    }

    @Test
    fun `hostile merchant text is stored as plain text and never interpreted`() = routeTest {
        for (hostile in listOf("<script>alert(1)</script>", "'; DROP TABLE lens_overrides; --", "Zepto\u202Eevil", "../../etc/passwd", "😀 Bar")) {
            val body = buildJsonObject { put("merchant", hostile); put("include", true) }.toString()
            val (status, saved) = call(HttpMethod.Put, "/api/v1/coach/lenses/nights_out/merchants", body)
            assertEquals(HttpStatusCode.OK, status, hostile)
            assertEquals(hostile.trim(), saved.data().str("merchant"))
        }
        assertEquals(5, repo.findByUser(user.id).size)
    }

    @Test
    fun `malformed bodies and path tricks are rejected`() = routeTest {
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Put, "/api/v1/coach/lenses/travel/merchants", """{"merchant":1}""").first)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Put, "/api/v1/coach/lenses/travel/merchants", "not json").first)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Put, "/api/v1/coach/lenses/..%2F..%2Fadmin/merchants", """{"merchant":"X","include":true}""").first)
    }

    @Test
    fun `the service exposes a lens by id and the owner's overrides`() = runBlocking<Unit> {
        assertEquals("Travel", service.lens("travel")?.label)
        assertEquals(null, service.lens("nope"))
        service.setOverride(user.id, "travel", "Goa Bus", include = true)
        assertEquals(true, service.overridesOf(user.id).choiceFor(LensId("travel"), "goa bus"))
    }
}
