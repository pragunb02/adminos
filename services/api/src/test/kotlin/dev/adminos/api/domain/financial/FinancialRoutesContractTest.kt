package dev.adminos.api.domain.financial

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.InMemoryAuditRepository
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Web ↔ API contract for list endpoints (AOS-107): camelCase query params, date ranges,
 * opaque cursors, validation errors. Uses production serialization, error handling and auth.
 */
class FinancialRoutesContractTest {

    private val jwtService = JwtService(AppConfig.load().auth)
    private val user = User(id = UUID.randomUUID(), email = "me@example.com", googleId = "g-1", role = UserRole.OWNER)
    private val jwt = jwtService.generateAccessToken(user)
    private val ist = ZoneId.of("Asia/Kolkata")

    private val transactions = InMemoryTransactionRepository()
    private val subscriptions = InMemorySubscriptionRepository()
    private val rules = InMemoryCategoryRuleRepository()

    private fun contractTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization()
            configureRequestId()
            configureErrorHandling()
            configureAuthentication(jwtService)
            routing {
                route("/api/v1") {
                    transactionRoutes(transactions, CategoryCorrectionService(rules, transactions), AuditService(InMemoryAuditRepository(), CoroutineScope(Dispatchers.Unconfined)))
                    subscriptionRoutes(subscriptions)
                }
            }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.getJson(path: String): Pair<HttpStatusCode, JsonObject> {
        val res = client.get(path) { cookie(ACCESS_TOKEN_COOKIE, jwt) }
        return res.status to Json.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    private fun txn(day: LocalDate, hour: Int, merchant: String = "Swiggy", recurring: Boolean = false) = Transaction(
        userId = user.id, type = TransactionType.DEBIT, amount = BigDecimal("100.00"), merchantName = merchant,
        isRecurring = recurring, transactedAt = day.atTime(hour, 0).atZone(ist).toInstant()
    )

    private fun JsonObject.ids() = this["data"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    @Test
    fun `date-only from and to cover whole days in India time`() = contractTest {
        val d = LocalDate.of(2026, 10, 1)
        runBlocking {
            transactions.save(txn(d.minusDays(1), 23))   // Sep 30, 23:00 IST → excluded
            transactions.save(txn(d, 0))                 // Oct 1, 00:00 IST → included
            transactions.save(txn(d, 23))                // Oct 1, 23:00 IST → included
            transactions.save(txn(d.plusDays(1), 0))     // Oct 2, 00:00 IST → excluded
        }
        val (status, body) = getJson("/api/v1/transactions?from=2026-10-01&to=2026-10-01&limit=50")
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(2, body.ids().size)
    }

    @Test
    fun `merchant and isRecurring filters are honoured`() = contractTest {
        runBlocking {
            transactions.save(txn(LocalDate.now(), 10, merchant = "Netflix", recurring = true))
            transactions.save(txn(LocalDate.now(), 11, merchant = "Swiggy"))
        }
        assertEquals(1, getJson("/api/v1/transactions?merchant=netflix").second.ids().size)
        assertEquals(1, getJson("/api/v1/transactions?isRecurring=true").second.ids().size)
    }

    @Test
    fun `cursor from page one fetches page two without overlap`() = contractTest {
        runBlocking { (1..5).forEach { transactions.save(txn(LocalDate.now().minusDays(it.toLong()), 12)) } }
        val (_, page1) = getJson("/api/v1/transactions?limit=2")
        val pagination = page1["pagination"]!!.jsonObject
        assertEquals(true, pagination["hasMore"]!!.jsonPrimitive.boolean)
        val cursor = pagination["cursor"]!!.jsonPrimitive.content

        val (status, page2) = getJson("/api/v1/transactions?limit=2&cursor=${cursor.encodeURLParameter()}")
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(2, page2.ids().size)
        assertTrue(page1.ids().intersect(page2.ids().toSet()).isEmpty())
    }

    @Test
    fun `invalid enum value is a 400 listing the allowed values`() = contractTest {
        val (status, body) = getJson("/api/v1/transactions?category=groceries")
        assertEquals(HttpStatusCode.BadRequest, status)
        val error = body["error"]!!.jsonObject
        assertEquals("VALIDATION_003", error["code"]!!.jsonPrimitive.content)
        assertTrue("food" in error["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `invalid date, boolean, limit and cursor are 400s`() = contractTest {
        for (q in listOf("from=01-10-2026", "isRecurring=yes", "limit=0", "limit=1000", "cursor=not-a-cursor")) {
            assertEquals(HttpStatusCode.BadRequest, getJson("/api/v1/transactions?$q").first, q)
        }
    }

    @Test
    fun `flagged=true returns only flagged subscriptions and status=flagged is rejected`() = contractTest {
        runBlocking {
            subscriptions.save(Subscription(userId = user.id, name = "Netflix", amount = BigDecimal("649"), billingCycle = BillingCycle.MONTHLY, isFlagged = true))
            subscriptions.save(Subscription(userId = user.id, name = "Spotify", amount = BigDecimal("119"), billingCycle = BillingCycle.MONTHLY))
        }
        val (status, body) = getJson("/api/v1/subscriptions?flagged=true")
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(1, body["data"]!!.jsonArray.size)
        assertEquals(1, body["pagination"]!!.jsonObject["total"]!!.jsonPrimitive.int)

        assertEquals(HttpStatusCode.BadRequest, getJson("/api/v1/subscriptions?status=flagged").first)
    }

    @Test
    fun `PATCH category remembers the payee by default and can opt out`() = contractTest {
        val d = LocalDate.of(2026, 10, 2)
        val (past, now, other) = runBlocking { listOf(txn(d, 9, "Ramu Tea"), txn(d, 18, "Ramu Tea"), txn(d, 10, "Bakery")).map { transactions.save(it) } }
        val res = client.patch("/api/v1/transactions/${now.id}") {
            cookie(ACCESS_TOKEN_COOKIE, jwt); contentType(ContentType.Application.Json); setBody("""{"category":"food","subcategory":"tea"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val data = Json.parseToJsonElement(res.bodyAsText()).jsonObject["data"]!!.jsonObject
        assertEquals("food", data["category"]!!.jsonPrimitive.content)
        assertEquals(TransactionCategory.FOOD, runBlocking { transactions.findById(past.id)!!.category })

        client.patch("/api/v1/transactions/${other.id}") {
            cookie(ACCESS_TOKEN_COOKIE, jwt); contentType(ContentType.Application.Json); setBody("""{"category":"food","remember":false}""")
        }
        assertEquals(null, runBlocking { rules.findFor(user.id, null, "bakery") })
    }
}
