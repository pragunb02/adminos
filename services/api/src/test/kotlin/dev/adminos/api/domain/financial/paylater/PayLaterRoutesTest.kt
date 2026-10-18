package dev.adminos.api.domain.financial.paylater

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.financial.*
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
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** GET /api/v1/pay-later (AOS-909). */
class PayLaterRoutesTest {
    private val jwtService = JwtService(AppConfig.load().auth)
    private val user = User(id = UUID.randomUUID(), email = "owner@example.com", googleId = "g-909")
    private val jwt = jwtService.generateAccessToken(user)
    private val bills = InMemoryBillRepository()
    private val txns = InMemoryTransactionRepository()

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureRequestBodyLimit(); configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            routing { route("/api/v1") { payLaterRoutes(PayLaterService(bills, txns)) } }
        }
        block()
    }

    @Test
    fun `debt outstanding per provider, with exact money strings, the user's own only`() = routeTest {
        runBlocking {
            bills.save(Bill(userId = user.id, billType = BillType.PAY_LATER, billerName = "Simpl", amount = BigDecimal("999.5"),
                dueDate = LocalDate.now().plusDays(3), createdAt = Instant.now().minusSeconds(86_400)))
            bills.save(Bill(userId = UUID.randomUUID(), billType = BillType.PAY_LATER, billerName = "LazyPay", amount = BigDecimal("50"), dueDate = LocalDate.now()))
        }
        val res = client.get("/api/v1/pay-later") { cookie(ACCESS_TOKEN_COOKIE, jwt) }
        assertEquals(HttpStatusCode.OK, res.status)
        val data = Json.parseToJsonElement(res.bodyAsText()).jsonObject.getValue("data").jsonObject
        assertEquals("999.50", data.getValue("outstanding").jsonPrimitive.content)
        val simpl = data.getValue("providers").jsonArray.single().jsonObject
        assertEquals("Simpl", simpl.getValue("provider").jsonPrimitive.content)
        assertEquals("upcoming", simpl.getValue("unpaidBills").jsonArray.single().jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals("999.50", simpl.getValue("gaps").jsonArray.single().jsonObject.getValue("unrecorded").jsonPrimitive.content)
        assertEquals(0, simpl.getValue("unbilledPurchases").jsonObject.getValue("count").jsonPrimitive.int)
    }

    @Test
    fun `signed out or with a forged token is refused`() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/pay-later").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/pay-later") { cookie(ACCESS_TOKEN_COOKIE, jwt.dropLast(4) + "AAAA") }.status)
    }
}
