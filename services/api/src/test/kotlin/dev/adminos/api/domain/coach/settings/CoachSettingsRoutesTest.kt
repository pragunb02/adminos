package dev.adminos.api.domain.coach.settings

import dev.adminos.api.config.AppConfig
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

/** /api/v1/coach/settings (AOS-904/905): defaults, partial updates, time validation, isolation, auth. */
class CoachSettingsRoutesTest {
    private val jwtService = JwtService(AppConfig.load().auth)
    private val owner = User(id = UUID.randomUUID(), email = "me@example.com", googleId = "g-1", role = UserRole.OWNER, timezone = "Asia/Kolkata")
    private val other = User(id = UUID.randomUUID(), email = "o@example.com", googleId = "g-2", role = UserRole.OWNER, timezone = "Asia/Kolkata")
    private val service = CoachSettingsService(InMemoryCoachSettingsRepository())

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureRequestBodyLimit(); configureAuthentication(jwtService)
            routing { route("/api/v1") { coachSettingsRoutes(service) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, body: String? = null, who: User = owner): Pair<HttpStatusCode, JsonObject> {
        val res = client.request("/api/v1/coach/settings") {
            this.method = method
            cookie(ACCESS_TOKEN_COOKIE, jwtService.generateAccessToken(who))
            asTheApp()
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        return res.status to Json.parseToJsonElement(res.bodyAsText()).jsonObject
    }
    private fun JsonObject.str(k: String) = getValue("data").jsonObject.getValue(k).jsonPrimitive.content

    @Test
    fun `defaults are alerts on, digest on at 21-30, not skipping zero days`() = routeTest {
        val (_, d) = call(HttpMethod.Get)
        assertEquals("true", d.str("limitAlerts")); assertEquals("true", d.str("eveningDigest"))
        assertEquals("21:30", d.str("digestTime")); assertEquals("false", d.str("digestSkipZeroDay"))
    }

    @Test
    fun `partial updates keep the rest, and are per user`() = routeTest {
        call(HttpMethod.Patch, """{"digestTime":"20:15","digestSkipZeroDay":true}""")
        val (_, d) = call(HttpMethod.Patch, """{"eveningDigest":false}""")
        assertEquals("20:15", d.str("digestTime")); assertEquals("true", d.str("digestSkipZeroDay")); assertEquals("false", d.str("eveningDigest"))
        assertEquals("21:30", call(HttpMethod.Get, who = other).second.str("digestTime"))
    }

    @Test
    fun `a bad time is a 400 and changes nothing`() = routeTest {
        for (bad in listOf("24:00", "9:30", "21:30:00", "21h30", "<script>", "")) {
            val (status, body) = call(HttpMethod.Patch, buildJsonObject { put("digestTime", bad) }.toString())
            assertEquals(HttpStatusCode.BadRequest, status, bad)
            assertTrue(body.getValue("error").jsonObject.getValue("message").jsonPrimitive.content.contains("digestTime"), bad)
        }
        assertEquals("21:30", call(HttpMethod.Get).second.str("digestTime"))
    }

    @Test
    fun `needs a session for reads and changes`() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/coach/settings").status)
        assertEquals(HttpStatusCode.Unauthorized, client.patch("/api/v1/coach/settings") { contentType(ContentType.Application.Json); setBody("""{"eveningDigest":false}""") }.status)
    }
}
