package dev.adminos.api.domain.financial.grouping

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.InMemoryAuditRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AOS-709: /merchants/merge-proposals over HTTP, including what an attacker would try. */
class MerchantGroupingRoutesTest {
    private val config = AppConfig.load().auth
    private val jwt = JwtService(config)
    private val owner = User(id = UUID.randomUUID(), email = "owner@example.com", googleId = "g-owner", role = UserRole.OWNER)
    private val stranger = User(id = UUID.randomUUID(), email = "stranger@example.com", googleId = "g-x", role = UserRole.OWNER)
    private val transactions = InMemoryTransactionRepository()
    private val aliases = InMemoryMerchantAliasRepository()
    private val service = MerchantGroupingService(transactions, aliases, UserCalendar(InMemoryUserRepository()),
        AuditService(InMemoryAuditRepository(), CoroutineScope(Dispatchers.Unconfined)))

    private suspend fun pay(merchant: String, user: UUID = owner.id) = transactions.save(Transaction(userId = user, type = TransactionType.DEBIT,
        amount = BigDecimal("199.00"), merchantName = merchant, paymentMethod = PaymentMethod.CARD_CREDIT, transactedAt = Instant.now().minusSeconds(86_400)))

    private fun routes(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwt)
            routing { route("/api/v1") { merchantGroupingRoutes(service) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, path: String, token: String? = jwt.generateAccessToken(owner)): Pair<HttpStatusCode, JsonObject> {
        val res = client.request(path) { this.method = method; token?.let { cookie(ACCESS_TOKEN_COOKIE, it) } }
        return res.status to Json.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    private fun JsonObject.code() = this["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content

    @Test
    fun `the owner sees proposals with names, counts and reasons`() = routes {
        pay("NETFLIX.COM"); pay("NETFLIX.COM"); pay("Netflix Ent")
        val (status, body) = call(HttpMethod.Get, "/api/v1/merchants/merge-proposals")
        assertEquals(HttpStatusCode.OK, status)
        val p = body["data"]!!.jsonArray.single().jsonObject
        assertEquals("NETFLIX.COM", p["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("NETFLIX.COM" to 2, "Netflix Ent" to 1), p["names"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["payments"]!!.jsonPrimitive.int })
        assertEquals(listOf("the same name once cleaned", "the names look alike"), p["reasons"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `confirming and rejecting answer once, then the proposal is gone`() = routes {
        pay("Kukufm"); pay("Kuku Fm")
        val id = service.proposals(owner.id).single().id
        val (ok, body) = call(HttpMethod.Post, "/api/v1/merchants/merge-proposals/$id/confirm")
        assertEquals(HttpStatusCode.OK, ok)
        assertEquals(true, body["data"]!!.jsonObject["merged"]!!.jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/api/v1/merchants/merge-proposals/$id/reject").first)
        pay("Hoichoi"); pay("Hoichoi Technologies")
        val other = service.proposals(owner.id).single().id
        val (kept, keptBody) = call(HttpMethod.Post, "/api/v1/merchants/merge-proposals/$other/reject")
        assertEquals(HttpStatusCode.OK, kept)
        assertEquals(false, keptBody["data"]!!.jsonObject["merged"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `without a session, or with a forged one, nothing is listed or changed`() = routes {
        pay("Kukufm"); pay("Kuku Fm")
        val id = service.proposals(owner.id).single().id
        val forged = JwtService(config.copy(jwtSecret = "an-attacker-secret-that-is-32-bytes-long!!")).generateAccessToken(owner)
        for (token in listOf(null, forged, "not-a-jwt")) {
            assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/api/v1/merchants/merge-proposals", token).first)
            assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Post, "/api/v1/merchants/merge-proposals/$id/confirm", token).first)
        }
        assertEquals(emptyList(), aliases.aliases(owner.id))
    }

    @Test
    fun `another user's proposal id is 404 and changes nothing for either user`() = routes {
        pay("Kukufm"); pay("Kuku Fm")
        val id = service.proposals(owner.id).single().id
        val (status, body) = call(HttpMethod.Post, "/api/v1/merchants/merge-proposals/$id/confirm", jwt.generateAccessToken(stranger))
        assertEquals(HttpStatusCode.NotFound, status)
        assertEquals("MERCHANT_MERGE_001", body.code())
        assertEquals(emptyList(), aliases.aliases(owner.id))
        assertEquals(emptyList(), aliases.aliases(stranger.id))
        assertEquals(1, service.proposals(owner.id).size)
    }

    @Test
    fun `malformed ids are 400 and unknown actions are not routes`() = routes {
        for (bad in listOf("xyz", "../../etc", "1%3BDROP%20TABLE%20merchant_aliases", "%00")) {
            val res = client.request("/api/v1/merchants/merge-proposals/$bad/confirm") { method = HttpMethod.Post; cookie(ACCESS_TOKEN_COOKIE, jwt.generateAccessToken(owner)) }
            assertTrue(res.status == HttpStatusCode.BadRequest || res.status == HttpStatusCode.NotFound, "$bad → ${res.status}")
            if (res.status == HttpStatusCode.BadRequest) assertEquals("VALIDATION_003", Json.parseToJsonElement(res.bodyAsText()).jsonObject.code())
        }
        val res = client.request("/api/v1/merchants/merge-proposals/${UUID.randomUUID()}/delete") { method = HttpMethod.Post; cookie(ACCESS_TOKEN_COOKIE, jwt.generateAccessToken(owner)) }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `hostile merchant names come back as plain JSON strings`() = routes {
        val name = "<img src=x onerror=alert(1)> Shop"
        pay(name); pay("$name Pvt Ltd")
        val (_, body) = call(HttpMethod.Get, "/api/v1/merchants/merge-proposals")
        val names = body["data"]!!.jsonArray.single().jsonObject["names"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf(name, "$name Pvt Ltd"), names)
    }
}
