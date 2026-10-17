package dev.adminos.api.domain.financial.receipts

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.receipts.ReceiptFixtures.payment
import dev.adminos.api.domain.financial.receipts.ReceiptFixtures.receipt
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
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val APP_ORIGIN = "http://localhost:3000"

/** GET /leaks/fees, GET /receipts, POST and DELETE /receipts/{id}/match (AOS-903). */
class ReceiptRoutesTest {
    private val jwtService = JwtService(AppConfig.load().auth)
    private val user = User(id = UUID.randomUUID(), email = "owner@example.in", googleId = "g-903")
    private val jwt = jwtService.generateAccessToken(user)
    private val receipts = InMemoryOrderReceiptRepository()
    private val txns = InMemoryTransactionRepository()
    private val matching = ReceiptMatchingService(receipts, txns)
    private val thisMonth = YearMonth.from(LocalDate.now(ZoneId.of("Asia/Kolkata")))
    private val now = Instant.now()

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureRequestBodyLimit(); configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            routing { route("/api/v1") { receiptRoutes(HiddenFeesService(receipts), matching) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, path: String, body: String? = null, auth: Boolean = true): Pair<HttpStatusCode, JsonObject> {
        val res = client.request("/api/v1$path") {
            this.method = method
            if (auth) cookie(ACCESS_TOKEN_COOKIE, jwt)
            if (method != HttpMethod.Get) header(HttpHeaders.Origin, APP_ORIGIN) // the app's own origin, as the browser sends it
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        val text = res.bodyAsText()
        return res.status to (if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject)
    }

    private fun JsonObject.data() = getValue("data")
    private fun JsonObject.errorCode() = getValue("error").jsonObject.getValue("code").jsonPrimitive.content

    @Test
    fun `this month's hidden fees by app, with exact money strings`() = routeTest {
        runBlocking { receipts.save(receipt(user.id, at = now)) }
        val (status, body) = call(HttpMethod.Get, "/leaks/fees")
        assertEquals(HttpStatusCode.OK, status)
        val data = body.data().jsonObject
        assertEquals(thisMonth.toString(), data.getValue("from").jsonPrimitive.content)
        assertEquals("INR", data.getValue("currency").jsonPrimitive.content)
        val month = data.getValue("months").jsonArray.single().jsonObject
        val app = month.getValue("apps").jsonArray.single().jsonObject
        assertEquals("Swiggy", app.getValue("app").jsonPrimitive.content)
        assertEquals("food_delivery", app.getValue("vertical").jsonPrimitive.content)
        assertEquals("45.00", app.getValue("hiddenFees").jsonPrimitive.content)
        assertEquals("20.00", app.getValue("tips").jsonPrimitive.content)
        assertEquals("0.0924", app.getValue("feeShare").jsonPrimitive.content)
        assertEquals("delivery", app.getValue("fees").jsonArray.first().jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals("487.00", month.getValue("totals").jsonObject.getValue("orderTotal").jsonPrimitive.content)
    }

    @Test
    fun `a range of months, and bad ranges are 400`() = routeTest {
        val from = thisMonth.minusMonths(2)
        val (ok, body) = call(HttpMethod.Get, "/leaks/fees?from=$from&to=$thisMonth")
        assertEquals(HttpStatusCode.OK, ok)
        assertEquals(3, body.data().jsonObject.getValue("months").jsonArray.size)
        assertEquals(1, call(HttpMethod.Get, "/leaks/fees?from=$from").second.data().jsonObject.getValue("months").jsonArray.size, "from alone is one month")
        for (bad in listOf("from=October", "from=$thisMonth&to=${thisMonth.minusMonths(1)}", "from=${thisMonth.minusMonths(12)}&to=$thisMonth",
            "from=1990-01", "to=2099-01", "from=2026-13")) {
            val (status, e) = call(HttpMethod.Get, "/leaks/fees?$bad")
            assertEquals(HttpStatusCode.BadRequest, status, bad)
            assertEquals("VALIDATION_003", e.errorCode(), bad)
        }
    }

    @Test
    fun `the receipts of a month, filtered by app, and a bad app name is 400`() = routeTest {
        runBlocking {
            receipts.save(receipt(user.id, at = now))
            receipts.save(receipt(user.id, app = "Zepto", at = now, vertical = ReceiptVertical.QUICK_COMMERCE))
            receipts.save(receipt(UUID.randomUUID(), at = now)) // someone else's
        }
        val (status, body) = call(HttpMethod.Get, "/receipts?month=$thisMonth")
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(2, body.data().jsonArray.size)
        val zepto = call(HttpMethod.Get, "/receipts?app=Zepto").second.data().jsonArray.single().jsonObject
        assertEquals("unmatched", zepto.getValue("matchState").jsonPrimitive.content)
        assertEquals("487.00", zepto.getValue("orderTotal").jsonPrimitive.content)
        assertEquals("0.00", zepto.getValue("refunded").jsonPrimitive.content)
        assertEquals(true, zepto.getValue("counts").jsonPrimitive.boolean)
        assertTrue(zepto.getValue("transactionId") is JsonNull)
        for (bad in listOf("%3Cscript%3E", "x".repeat(41), "1abc")) {
            assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Get, "/receipts?app=$bad").first, bad)
        }
    }

    @Test
    fun `confirming and rejecting a payment`() = routeTest {
        val (r, p) = runBlocking { receipts.save(receipt(user.id, at = now)) to txns.save(payment(user.id, at = now.plusSeconds(30), merchant = "PayU")) }
        val (ok, body) = call(HttpMethod.Post, "/receipts/${r.id}/match", """{"transactionId":"${p.id}"}""")
        assertEquals(HttpStatusCode.OK, ok)
        assertEquals("matched", body.data().jsonObject.getValue("matchState").jsonPrimitive.content)
        assertEquals("user", body.data().jsonObject.getValue("matchedBy").jsonPrimitive.content)
        val (rejected, after) = call(HttpMethod.Delete, "/receipts/${r.id}/match")
        assertEquals(HttpStatusCode.OK, rejected)
        assertEquals("unmatched", after.data().jsonObject.getValue("matchState").jsonPrimitive.content, "the rejected payment isn't proposed again")
        val (nothing, e) = call(HttpMethod.Delete, "/receipts/${r.id}/match")
        assertEquals(HttpStatusCode.Conflict, nothing)
        assertEquals("RECEIPT_005", e.errorCode())
    }

    @Test
    fun `another user's receipt or payment is 404, never 403`() = routeTest {
        val (theirs, mine, theirPayment) = runBlocking {
            Triple(receipts.save(receipt(UUID.randomUUID(), at = now)), receipts.save(receipt(user.id, at = now)), txns.save(payment(UUID.randomUUID())))
        }
        val myPayment = runBlocking { txns.save(payment(user.id, at = now)) }
        assertEquals("RECEIPT_001", call(HttpMethod.Post, "/receipts/${theirs.id}/match", """{"transactionId":"${myPayment.id}"}""").let { (s, b) -> assertEquals(HttpStatusCode.NotFound, s); b.errorCode() })
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, "/receipts/${theirs.id}/match").first)
        assertEquals("RECEIPT_002", call(HttpMethod.Post, "/receipts/${mine.id}/match", """{"transactionId":"${theirPayment.id}"}""").let { (s, b) -> assertEquals(HttpStatusCode.NotFound, s); b.errorCode() })
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/receipts/${UUID.randomUUID()}/match", """{"transactionId":"${myPayment.id}"}""").first)
    }

    @Test
    fun `a payment that can't pay an order, or is taken, is refused`() = routeTest {
        val (a, b) = runBlocking { receipts.save(receipt(user.id, at = now)) to receipts.save(receipt(user.id, at = now)) }
        val (refund, spend) = runBlocking {
            txns.save(payment(user.id).copy(nature = TransactionNature.REFUND)) to txns.save(payment(user.id, merchant = "PayU"))
        }
        val (s1, e1) = call(HttpMethod.Post, "/receipts/${a.id}/match", """{"transactionId":"${refund.id}"}""")
        assertEquals(HttpStatusCode.BadRequest, s1)
        assertEquals("RECEIPT_003", e1.errorCode())
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/receipts/${a.id}/match", """{"transactionId":"${spend.id}"}""").first)
        val (s2, e2) = call(HttpMethod.Post, "/receipts/${b.id}/match", """{"transactionId":"${spend.id}"}""")
        assertEquals(HttpStatusCode.Conflict, s2)
        assertEquals("RECEIPT_004", e2.errorCode())
    }

    @Test
    fun `malformed ids and bodies are 400`() = routeTest {
        val r = runBlocking { receipts.save(receipt(user.id, at = now)) }
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/receipts/not-a-uuid/match", """{"transactionId":"${UUID.randomUUID()}"}""").first)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Delete, "/receipts/not-a-uuid/match").first)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/receipts/${r.id}/match", """{"transactionId":"x"}""").first)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/receipts/${r.id}/match", """{"transactionId":42}""").first)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/receipts/${r.id}/match", "{").first)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/receipts/${r.id}/match", "{}").first)
    }

    @Test
    fun `an oversized body is refused before it is read`() = routeTest {
        val big = """{"transactionId":"${"x".repeat(1_100_000)}"}"""
        val (status, e) = call(HttpMethod.Post, "/receipts/${UUID.randomUUID()}/match", big)
        assertEquals(HttpStatusCode.PayloadTooLarge, status)
        assertEquals("REQUEST_TOO_LARGE", e.errorCode())
    }

    @Test
    fun `every receipt route requires sign-in`() = routeTest {
        val id = UUID.randomUUID()
        for ((method, path) in listOf(HttpMethod.Get to "/leaks/fees", HttpMethod.Get to "/receipts", HttpMethod.Post to "/receipts/$id/match", HttpMethod.Delete to "/receipts/$id/match")) {
            assertEquals(HttpStatusCode.Unauthorized, call(method, path, body = if (method == HttpMethod.Post) "{}" else null, auth = false).first, "$method $path")
        }
        val forged = client.get("/api/v1/leaks/fees") { cookie(ACCESS_TOKEN_COOKIE, jwt.dropLast(4) + "AAAA") }
        assertEquals(HttpStatusCode.Unauthorized, forged.status)
    }
}
