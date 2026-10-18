package dev.adminos.api.domain.wealth.holdings

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.domain.wealth.imports.CasFixtures
import dev.adminos.api.domain.wealth.imports.CasImportServiceTest
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
import kotlin.test.assertFalse

/** GET /api/v1/wealth/holdings (AOS-820): shape, ownership and auth. */
class HoldingsRoutesTest {
    private val jwt = JwtService(AppConfig.load().auth)
    private val kit = CasImportServiceTest.Kit()
    private val owner = User(id = UUID.randomUUID(), email = "owner@example.com", googleId = "g-1", role = UserRole.OWNER, timezone = "Asia/Kolkata")
    private val other = User(id = UUID.randomUUID(), email = "other@example.com", googleId = "g-2", role = UserRole.OWNER, timezone = "Asia/Kolkata")
    private val service = HoldingsService(kit.ledger, kit.accounts, kit.instruments, kit.prices, kit.reconciliation)

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { this.config = MapApplicationConfig() }
        application {
            configureRequestId(); configureSerialization(); configureErrorHandling(); configureAuthentication(jwt)
            routing { route("/api/v1") { holdingsRoutes(service) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.get(who: User?) = client.get("/api/v1/wealth/holdings") {
        if (who != null) cookie(ACCESS_TOKEN_COOKIE, jwt.generateAccessToken(who))
    }

    @Test
    fun `the owner gets their holdings, someone else an empty overview`() = routeTest {
        runBlocking { kit.service.import(owner.id, "%PDF-".toByteArray(), CasFixtures.PASSWORD) }
        val res = get(owner)
        assertEquals(HttpStatusCode.OK, res.status)
        val data = Json.parseToJsonElement(res.bodyAsText()).jsonObject.getValue("data").jsonObject
        assertEquals("43500.00", data.getValue("value").jsonPrimitive.content)
        assertEquals(2, data.getValue("groups").jsonArray.sumOf { it.jsonObject.getValue("holdings").jsonArray.size })
        assertFalse("90001234" in res.bodyAsText())
        val theirs = Json.parseToJsonElement(get(other).bodyAsText()).jsonObject.getValue("data").jsonObject
        assertEquals(0, theirs.getValue("groups").jsonArray.size)
        assertEquals("0.00", theirs.getValue("value").jsonPrimitive.content)
    }

    @Test
    fun `a session is required`() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, get(null).status)
    }
}
