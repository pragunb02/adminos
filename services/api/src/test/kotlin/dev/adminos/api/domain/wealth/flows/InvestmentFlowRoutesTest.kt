package dev.adminos.api.domain.wealth.flows

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.domain.wealth.InMemoryInvestmentLedger
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
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** GET /api/v1/wealth/investment-flows (AOS-815): totals, statuses, range validation, auth, ownership. */
class InvestmentFlowRoutesTest {
    private val config = AppConfig.load()
    private val jwt = JwtService(config.auth)
    private val owner = User(id = UUID.randomUUID(), email = "owner@example.com", googleId = "g-1", role = UserRole.OWNER, timezone = "Asia/Kolkata")
    private val other = User(id = UUID.randomUUID(), email = "other@example.com", googleId = "g-2", role = UserRole.OWNER, timezone = "Asia/Kolkata")
    private val txns = InMemoryTransactionRepository()
    private val service = InvestmentFlowService(txns, InMemoryInvestmentLedger())

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { this.config = MapApplicationConfig() }
        application {
            configureRequestId(); configureSerialization(); configureErrorHandling(); configureAuthentication(jwt)
            routing { route("/api/v1") { investmentFlowRoutes(service) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.get(path: String, who: User? = owner) = client.get(path) {
        if (who != null) cookie(ACCESS_TOKEN_COOKIE, jwt.generateAccessToken(who))
    }

    @Test
    fun `the owner's investment flows come back with totals and review statuses`() = routeTest {
        runBlocking {
            txns.save(Transaction(userId = owner.id, type = TransactionType.DEBIT, amount = BigDecimal("10000.00"), nature = TransactionNature.INVESTMENT,
                merchantRaw = "ACH D- EXAMPLE MUTUAL FUND SIP", transactedAt = Instant.parse("2026-02-10T04:00:00Z")))
            txns.save(Transaction(userId = owner.id, type = TransactionType.DEBIT, amount = BigDecimal("250.00"), merchantName = "Swiggy",
                transactedAt = Instant.parse("2026-02-11T04:00:00Z")))
        }
        val res = get("/api/v1/wealth/investment-flows?from=2026-02-01&to=2026-02-28")
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val data = Json.parseToJsonElement(res.bodyAsText()).jsonObject.getValue("data").jsonObject
        assertEquals("10000.00", data.getValue("invested").jsonPrimitive.content)
        assertEquals("10000.00", data.getValue("net").jsonPrimitive.content)
        assertEquals(1, data.getValue("unmatched").jsonPrimitive.int)
        val flow = data.getValue("flows").jsonArray.single().jsonObject
        assertEquals("out", flow.getValue("direction").jsonPrimitive.content)
        assertEquals("unmatched", flow.getValue("status").jsonPrimitive.content)
        assertEquals("2026-02-10", flow.getValue("date").jsonPrimitive.content)

        val theirs = Json.parseToJsonElement(get("/api/v1/wealth/investment-flows?from=2026-02-01&to=2026-02-28", other).bodyAsText()).jsonObject
        assertEquals(0, theirs.getValue("data").jsonObject.getValue("flows").jsonArray.size)
    }

    @Test
    fun `ranges are validated and a session is required`() = routeTest {
        assertEquals(HttpStatusCode.BadRequest, get("/api/v1/wealth/investment-flows?from=2026-03-01&to=2026-02-01").status)
        assertEquals(HttpStatusCode.BadRequest, get("/api/v1/wealth/investment-flows?from=2020-01-01&to=2026-02-01").status)
        assertEquals(HttpStatusCode.BadRequest, get("/api/v1/wealth/investment-flows?from=yesterday").status)
        assertEquals(HttpStatusCode.OK, get("/api/v1/wealth/investment-flows").status, "defaults to the last 90 days")
        assertEquals(HttpStatusCode.Unauthorized, get("/api/v1/wealth/investment-flows", who = null).status)
    }
}
