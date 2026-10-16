package dev.adminos.api.domain.ingestion.cash

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.domain.financial.TransactionCategory
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
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** POST /api/v1/transactions/cash contract (AOS-204): camelCase body, 202, idempotent, 400 naming the field, auth required. */
class CashRoutesTest {
    private val jwtService = JwtService(AppConfig.load().auth)
    private val user = User(id = UUID.randomUUID(), email = "me@example.com", googleId = "g-1", role = UserRole.OWNER)
    private val jwt = jwtService.generateAccessToken(user)
    private val events = InMemoryIngestEventRepository()

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            routing { route("/api/v1") { cashRoutes(CashIntake(IngestEventService(events))) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.post(body: String, auth: Boolean = true): Pair<HttpStatusCode, JsonObject> {
        val res = client.post("/api/v1/transactions/cash") {
            if (auth) cookie(ACCESS_TOKEN_COOKIE, jwt)
            contentType(ContentType.Application.Json); setBody(body)
        }
        return res.status to Json.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    @Test
    fun `saves once and reports the retry as a duplicate`() = routeTest {
        val body = """{"clientRef":"${UUID.randomUUID()}","amount":"249.50","category":"food","note":"chai"}"""
        val (s1, b1) = post(body)
        assertEquals(HttpStatusCode.Accepted, s1)
        assertEquals(true, b1["data"]!!.jsonObject["saved"]!!.jsonPrimitive.boolean)
        val (_, b2) = post(body)
        assertEquals(true, b2["data"]!!.jsonObject["duplicate"]!!.jsonPrimitive.boolean)
        val e = runBlocking { events.findByUser(user.id) }.single()
        assertEquals(TransactionCategory.FOOD, e.category); assertEquals(0, "249.50".toBigDecimal().compareTo(e.amount))
        assertEquals(user.id, e.userId)
    }

    @Test
    fun `bad input is a 400 naming the field`() = routeTest {
        val r = UUID.randomUUID()
        for ((body, field) in listOf(
            """{"clientRef":"$r","amount":"abc","category":"food"}""" to "amount",
            """{"clientRef":"$r","amount":"10","category":"groceries"}""" to "category",
            """{"clientRef":"$r","amount":"10","category":"food","occurredAt":"yesterday"}""" to "occurredAt",
        )) {
            val (status, json) = post(body)
            assertEquals(HttpStatusCode.BadRequest, status, body)
            assertTrue(json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.startsWith(field), json.toString())
        }
    }

    @Test
    fun `requires a signed-in user`() = routeTest {
        val res = client.post("/api/v1/transactions/cash") { contentType(ContentType.Application.Json); setBody("{}") }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }
}
