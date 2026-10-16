package dev.adminos.api.domain.ingestion.events

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
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Contract of POST /api/v1/ingest/events (AOS-113) with production serialization, errors and auth. */
class IngestEventRoutesTest {

    private val jwtService = JwtService(AppConfig.load().auth)
    private val user = User(id = UUID.randomUUID(), email = "me@example.com", googleId = "g-1", role = UserRole.OWNER)
    private val jwt = jwtService.generateAccessToken(user)
    private val repo = InMemoryIngestEventRepository()

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            routing { route("/api/v1") { ingestEventRoutes(IngestEventService(repo)) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.post(body: String, auth: Boolean = true) = client.post("/api/v1/ingest/events") {
        if (auth) cookie(ACCESS_TOKEN_COOKIE, jwt)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun event(ref: String = "sms-1", amount: String = "\"249.50\"", occurredAt: String = "2026-10-01T09:12:00+05:30", extra: String = "") =
        """{"source":"sms","sourceRef":"$ref","occurredAt":"$occurredAt","direction":"debit","amount":$amount,
            "merchantRaw":"SWIGGY*ORDER","accountHint":"1234","paymentMethod":"upi"$extra}"""

    @Test
    fun `valid batch is accepted with counts and stored for the user`() = routeTest {
        val res = post("""{"events":[${event("a")},${event("b")}]}""")
        assertEquals(HttpStatusCode.Accepted, res.status)
        assertTrue("\"accepted\":2" in res.bodyAsText() && "\"duplicates\":0" in res.bodyAsText(), res.bodyAsText())
        val stored = runBlocking { repo.findByUser(user.id) }
        assertEquals(2, stored.size)
        assertEquals(0, BigDecimal("249.50").compareTo(stored.first().amount))
    }

    @Test
    fun `retrying the same request creates no duplicates`() = routeTest {
        post("""{"events":[${event("same")}]}""")
        val res = post("""{"events":[${event("same")}]}""")
        assertTrue("\"accepted\":0" in res.bodyAsText() && "\"duplicates\":1" in res.bodyAsText())
    }

    @Test
    fun `requires authentication`() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, post("""{"events":[${event()}]}""", auth = false).status)
    }

    @Test
    fun `amount as a JSON number is rejected (money must be a decimal string)`() = routeTest {
        val res = post("""{"events":[${event(amount = "249.5")}]}""")
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue("VALIDATION_004" in res.bodyAsText(), res.bodyAsText())
    }

    @Test
    fun `malformed json is a 400 not a 500`() = routeTest {
        assertEquals(HttpStatusCode.BadRequest, post("{not json").status)
    }

    @Test
    fun `invalid fields are 400s naming the field`() = routeTest {
        val cases = mapOf(
            event(amount = "\"abc\"") to "events[0].amount",
            event(occurredAt = "16/10/2026") to "events[0].occurredAt",
            event().replace("\"debit\"", "\"sideways\"") to "events[0].direction",
            event().replace("\"sms\"", "\"telegram\"") to "events[0].source",
            event().replace("\"1234\"", "\"123456\"") to "events[0]"
        )
        for ((e, field) in cases) {
            val res = post("""{"events":[$e]}""")
            assertEquals(HttpStatusCode.BadRequest, res.status, e)
            assertTrue(field in res.bodyAsText(), "expected '$field' in ${res.bodyAsText()}")
        }
    }

    @Test
    fun `raw message text is not part of the contract`() = routeTest {
        // Unknown fields are ignored by the decoder, and nothing named like raw text is stored (D-33).
        post("""{"events":[${event("raw", extra = ",\"rawText\":\"Rs 249.50 debited...\"")}]}""")
        val stored = runBlocking { repo.findByUser(user.id) }.single { it.sourceRef == "raw" }
        assertTrue(stored.metadata == null && "debited" !in stored.toString())
    }
}
