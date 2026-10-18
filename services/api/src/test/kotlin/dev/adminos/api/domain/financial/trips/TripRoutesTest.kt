package dev.adminos.api.domain.financial.trips

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.financial.links.InMemoryTransactionLinkRepository
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
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

private const val APP_ORIGIN = "http://localhost:3000"

/** The trips API (AOS-910): list, detail, candidates and edits; auth, ownership, validation and the body cap. */
class TripRoutesTest {
    private val jwtService = JwtService(AppConfig.load().auth)
    private val user = User(id = UUID.randomUUID(), email = "owner@example.in", googleId = "g-910")
    private val jwt = jwtService.generateAccessToken(user)
    private val txns = InMemoryTransactionRepository()
    private val service = TripService(txns, InMemoryTransactionLinkRepository(), InMemoryTravelBookingRepository(), InMemoryTripEditRepository())
    private val today = LocalDate.now(ZoneId.of("Asia/Kolkata"))

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureRequestBodyLimit(); configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            routing { route("/api/v1") { tripRoutes(service) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, path: String, body: String? = null, auth: Boolean = true): Pair<HttpStatusCode, JsonObject> {
        val res = client.request("/api/v1$path") {
            this.method = method
            if (auth) cookie(ACCESS_TOKEN_COOKIE, jwt)
            if (method != HttpMethod.Get) header(HttpHeaders.Origin, APP_ORIGIN)
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        val text = res.bodyAsText()
        return res.status to (if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject)
    }

    private fun JsonObject.data() = getValue("data")
    private fun JsonObject.code() = getValue("error").jsonObject.getValue("code").jsonPrimitive.content

    private fun seed(userId: UUID = user.id): Pair<Transaction, Transaction> = runBlocking {
        val at = today.atTime(0, 5).atZone(ZoneId.of("Asia/Kolkata")).toInstant()
        txns.save(Transaction(userId = userId, type = TransactionType.DEBIT, amount = BigDecimal("4500.00"), merchantName = "IndiGo",
            category = TransactionCategory.TRANSPORT, paymentMethod = PaymentMethod.UPI, transactedAt = at)) to
            txns.save(Transaction(userId = userId, type = TransactionType.DEBIT, amount = BigDecimal("350.00"), merchantName = "Uber",
                category = TransactionCategory.TRANSPORT, paymentMethod = PaymentMethod.UPI, transactedAt = at.plusSeconds(3600)))
    }

    private suspend fun ApplicationTestBuilder.tripId(): String =
        call(HttpMethod.Get, "/trips").second.data().jsonObject.getValue("trips").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content

    @Test
    fun `this year's trips, their total and the detail`() = routeTest {
        val (ticket, cab) = seed()
        seed(UUID.randomUUID()) // someone else's
        val (status, body) = call(HttpMethod.Get, "/trips")
        assertEquals(HttpStatusCode.OK, status)
        val data = body.data().jsonObject
        assertEquals(today.year, data.getValue("year").jsonPrimitive.int)
        assertEquals("4850.00", data.getValue("total").jsonPrimitive.content)
        val trip = data.getValue("trips").jsonArray.single().jsonObject
        assertEquals(listOf("flight"), trip.getValue("modes").jsonArray.map { it.jsonPrimitive.content })
        val (ok, detail) = call(HttpMethod.Get, "/trips/${trip.getValue("id").jsonPrimitive.content}")
        assertEquals(HttpStatusCode.OK, ok)
        val roles = detail.data().jsonObject.getValue("members").jsonArray.associate {
            it.jsonObject.getValue("transaction").jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject.getValue("role").jsonPrimitive.content
        }
        assertEquals(mapOf(ticket.id.toString() to "booking", cab.id.toString() to "spend"), roles)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/trips?year=${today.year - 1}").first)
    }

    @Test
    fun `rename, add, remove and dismiss`() = routeTest {
        val (_, cab) = seed()
        val extra = runBlocking {
            txns.save(Transaction(userId = user.id, type = TransactionType.DEBIT, amount = BigDecimal("2000.00"), merchantName = "Sample Stay",
                category = TransactionCategory.OTHER, transactedAt = today.atTime(20, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant()))
        }
        val id = tripId()
        assertEquals(extra.id.toString(), call(HttpMethod.Get, "/trips/$id/candidates").second.data().jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("Home", call(HttpMethod.Patch, "/trips/$id", """{"name":"Home"}""").second.data().jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("6850.00", call(HttpMethod.Post, "/trips/$id/transactions", """{"transactionId":"${extra.id}"}""").second.data().jsonObject.getValue("total").jsonPrimitive.content)
        assertEquals("6500.00", call(HttpMethod.Delete, "/trips/$id/transactions/${cab.id}").second.data().jsonObject.getValue("total").jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Delete, "/trips/$id").first)
        assertEquals(0, call(HttpMethod.Get, "/trips").second.data().jsonObject.getValue("trips").jsonArray.size)
    }

    @Test
    fun `untrusted names are stored as text, and bad input is 400`() = routeTest {
        seed()
        val id = tripId()
        val hostile = "<img src=x onerror=alert(1)>"
        assertEquals(hostile, call(HttpMethod.Patch, "/trips/$id", """{"name":"$hostile"}""").second.data().jsonObject.getValue("name").jsonPrimitive.content)
        for ((method, path, body) in listOf(
            Triple(HttpMethod.Patch, "/trips/$id", """{"name":""}"""),
            Triple(HttpMethod.Patch, "/trips/$id", """{"name":"${"x".repeat(81)}"}"""),
            Triple(HttpMethod.Patch, "/trips/$id", "{"),
            Triple(HttpMethod.Post, "/trips/$id/transactions", """{"transactionId":"nope"}"""),
            Triple(HttpMethod.Get, "/trips/not-a-uuid", null),
            Triple(HttpMethod.Get, "/trips?year=1990", null),
            Triple(HttpMethod.Get, "/trips?year=abc", null),
            Triple(HttpMethod.Delete, "/trips/$id/transactions/not-a-uuid", null)
        )) {
            assertEquals(HttpStatusCode.BadRequest, call(method, path, body).first, "$method $path")
        }
        val (tooBig, e) = call(HttpMethod.Patch, "/trips/$id", """{"name":"${"x".repeat(1_100_000)}"}""")
        assertEquals(HttpStatusCode.PayloadTooLarge, tooBig)
        assertEquals("REQUEST_TOO_LARGE", e.code())
    }

    @Test
    fun `another user's trip or transaction is 404, a non-spend is 400`() = routeTest {
        seed()
        val id = tripId()
        val (theirTicket, _) = seed(UUID.randomUUID())
        val credit = runBlocking { txns.save(Transaction(userId = user.id, type = TransactionType.CREDIT, amount = BigDecimal.TEN, transactedAt = Instant.now())) }
        assertEquals("TRIP_001", call(HttpMethod.Get, "/trips/${UUID.randomUUID()}").second.code())
        assertEquals("TRIP_001", call(HttpMethod.Patch, "/trips/${UUID.randomUUID()}", """{"name":"x"}""").second.code())
        assertEquals("TRIP_002", call(HttpMethod.Post, "/trips/$id/transactions", """{"transactionId":"${theirTicket.id}"}""").second.code())
        assertEquals("TRIP_002", call(HttpMethod.Delete, "/trips/$id/transactions/${theirTicket.id}").second.code())
        assertEquals("TRIP_003", call(HttpMethod.Post, "/trips/$id/transactions", """{"transactionId":"${credit.id}"}""").second.code())
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/trips/${UUID.randomUUID()}/candidates").first)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, "/trips/${UUID.randomUUID()}").first)
    }

    @Test
    fun `every trip route requires sign-in`() = routeTest {
        val id = UUID.randomUUID()
        for ((method, path) in listOf(HttpMethod.Get to "/trips", HttpMethod.Get to "/trips/$id", HttpMethod.Get to "/trips/$id/candidates",
            HttpMethod.Patch to "/trips/$id", HttpMethod.Delete to "/trips/$id", HttpMethod.Post to "/trips/$id/transactions",
            HttpMethod.Delete to "/trips/$id/transactions/$id")) {
            assertEquals(HttpStatusCode.Unauthorized, call(method, path, body = if (method == HttpMethod.Patch || method == HttpMethod.Post) "{}" else null, auth = false).first, "$method $path")
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/trips") { cookie(ACCESS_TOKEN_COOKIE, jwt.dropLast(4) + "AAAA") }.status)
    }
}
