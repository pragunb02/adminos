package dev.adminos.api.domain.balances

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** GET /api/v1/balances (AOS-720): the session's own latest balances, labelled by age. */
class BalanceRoutesTest {
    private val jwtService = JwtService(AppConfig.load().auth)
    private val me = User(id = UUID.randomUUID(), email = "owner@example.com", googleId = "g-720", timezone = "Asia/Kolkata")
    private val other = User(id = UUID.randomUUID(), email = "other@example.com", googleId = "g-721", timezone = "Asia/Kolkata")
    /** 20 Oct 2026, 10:00 in India. */
    private val service = BalanceService(InMemoryBalanceRepository(), Clock.fixed(Instant.parse("2026-10-20T04:30:00Z"), ZoneOffset.UTC))

    private fun ref(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            routing { route("/api/v1") { balanceRoutes(service) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.get(user: User): JsonObject {
        val res = client.get("/api/v1/balances") { cookie(ACCESS_TOKEN_COOKIE, jwtService.generateAccessToken(user)) }
        assertEquals(HttpStatusCode.OK, res.status)
        return Json.parseToJsonElement(res.bodyAsText()).jsonObject["data"]!!.jsonObject
    }

    @Test
    fun `lists each account's latest balance with its age and the counted total`() = routeTest {
        runBlocking {
            service.record(BalanceObservation(me.id, "hdfc", "1234", BigDecimal("12000.50"), Instant.parse("2026-10-19T06:00:00Z"), BalanceSource.SMS, ref("1")))
            service.record(BalanceObservation(me.id, "sbi", "5678", BigDecimal("-200.00"), Instant.parse("2026-10-15T18:29:59Z"), BalanceSource.STATEMENT, ref("2")))
            service.record(BalanceObservation(me.id, null, "9012", BigDecimal("999.00"), Instant.parse("2026-10-01T06:00:00Z"), BalanceSource.SMS, ref("3")))
        }
        val data = get(me)
        assertEquals("2026-10-20", data["today"]!!.jsonPrimitive.content)
        assertEquals("11800.50", data["total"]!!.jsonPrimitive.content)
        assertEquals(2, data["countedAccounts"]!!.jsonPrimitive.int)
        assertEquals(7, data["maxAgeDays"]!!.jsonPrimitive.int)
        val rows = data["accounts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("hdfc", "sbi", null), rows.map { it["bank"]!!.jsonPrimitive.contentOrNull })
        assertEquals(listOf("fresh", "aging", "stale"), rows.map { it["freshness"]!!.jsonPrimitive.content })
        assertEquals(listOf(true, true, false), rows.map { it["counted"]!!.jsonPrimitive.boolean })
        assertEquals(listOf("12000.50", "-200.00", "999.00"), rows.map { it["amount"]!!.jsonPrimitive.content })
        assertEquals(listOf("sms", "statement", "sms"), rows.map { it["source"]!!.jsonPrimitive.content })
        assertEquals("2026-10-15", rows[1]["observedOn"]!!.jsonPrimitive.content, "the day in India")
        assertEquals(listOf(1L, 5L, 19L), rows.map { it["ageDays"]!!.jsonPrimitive.long })
    }

    @Test
    fun `nothing known is an empty list, and another user's balances never show`() = routeTest {
        runBlocking { service.record(BalanceObservation(other.id, "hdfc", "4444", BigDecimal("77777.77"), Instant.parse("2026-10-19T06:00:00Z"), BalanceSource.SMS, ref("o"))) }
        val data = get(me)
        assertEquals(JsonArray(emptyList()), data["accounts"])
        assertEquals("0.00", data["total"]!!.jsonPrimitive.content)
        assertFalse("77777" in data.toString() || "4444" in data.toString())
        assertEquals("77777.77", get(other)["total"]!!.jsonPrimitive.content)
    }

    @Test
    fun `signed-out calls and forged or expired tokens are refused`() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/balances").status)
        val forged = jwtService.generateAccessToken(me).dropLast(3) + "abc"
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/balances") { cookie(ACCESS_TOKEN_COOKIE, forged) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/balances") { header(HttpHeaders.Authorization, "Bearer not.a.jwt") }.status)
    }
}
