package dev.adminos.api.domain.financial.splits

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplitRoutesTest {
    private val jwt = JwtService(AppConfig.load().auth)
    private val owner = User(id = UUID.randomUUID(), email = "me@example.com", googleId = "g-1", role = UserRole.OWNER)
    private val stranger = User(id = UUID.randomUUID(), email = "x@example.com", googleId = "g-2", role = UserRole.OWNER)
    private val txns = InMemoryTransactionRepository()
    private val repo = InMemorySplitRepository()
    private val service = SplitService(repo, txns, CategorizationService(), UserCalendar(InMemoryUserRepository()))
    private val csv = """
        Date,Description,Category,Cost,Currency,Dev P.,Asha R.
        2026-10-03,Dinner at Toit,Dining out,4000.00,INR,2000.00,-2000.00
        2026-10-04,<b>Cab</b>,Taxi,900.00,INR,-450.00,450.00
    """.trimIndent().toByteArray()

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwt)
            routing { route("/api/v1") { splitRoutes(service) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.upload(file: ByteArray?, me: String? = "Dev P.", user: User? = owner) =
        client.post("/api/v1/imports/splitwise") {
            user?.let { cookie(ACCESS_TOKEN_COOKIE, jwt.generateAccessToken(it)) }
            setBody(MultiPartFormDataContent(formData {
                if (file != null) append("file", file, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"export.csv\"") })
                if (me != null) append("me", me)
            }))
        }

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, path: String, user: User? = owner, json: String? = null) =
        client.request("/api/v1$path") {
            this.method = method
            user?.let { cookie(ACCESS_TOKEN_COOKIE, jwt.generateAccessToken(it)) }
            if (json != null) { contentType(ContentType.Application.Json); setBody(json) }
        }

    private fun data(res: String) = Json.parseToJsonElement(res).jsonObject["data"]!!

    @Test
    fun `an upload returns the counts, and Review lists the entries as text`() = routeTest {
        val res = upload(csv)
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val d = data(res.bodyAsText()).jsonObject
        assertEquals(2, d["rows"]!!.jsonPrimitive.int)
        assertEquals(1, d["applied"]!!.jsonPrimitive.int, "the friend-paid cab")
        assertEquals(1, d["unmatched"]!!.jsonPrimitive.int, "no bank debit for the dinner yet")

        val list = data(call(HttpMethod.Get, "/splitwise/entries").bodyAsText()).jsonArray
        assertEquals(2, list.size)
        val cab = list.first { it.jsonObject["effect"]!!.jsonPrimitive.content == "owed_spend" }.jsonObject
        assertEquals("<b>Cab</b>", cab["description"]!!.jsonPrimitive.content, "plain text; the web renders it as text")
        assertEquals("450.00", cab["ownerShare"]!!.jsonPrimitive.content)
        assertEquals("450.00", cab["transaction"]!!.jsonObject["amount"]!!.jsonPrimitive.content)
        assertEquals(1, data(call(HttpMethod.Get, "/splitwise/entries?state=unmatched&limit=5").bodyAsText()).jsonArray.size)

        val summary = data(call(HttpMethod.Get, "/splitwise/summary").bodyAsText()).jsonObject
        assertEquals("1550.00", summary["balances"]!!.jsonArray.single().jsonObject["net"]!!.jsonPrimitive.content)
        assertEquals(1, summary["counts"]!!.jsonObject["applied"]!!.jsonPrimitive.int)
        assertEquals(1, data(call(HttpMethod.Get, "/splitwise/imports").bodyAsText()).jsonArray.size)
    }

    @Test
    fun `every route needs a signed-in user`() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, upload(csv, user = null).status)
        val id = UUID.randomUUID()
        for ((m, p) in listOf(HttpMethod.Get to "/splitwise/entries", HttpMethod.Get to "/splitwise/summary", HttpMethod.Get to "/splitwise/imports",
            HttpMethod.Post to "/splitwise/entries/$id/confirm", HttpMethod.Post to "/splitwise/entries/$id/reject",
            HttpMethod.Post to "/splitwise/entries/$id/ignore", HttpMethod.Post to "/splitwise/entries/$id/undo",
            HttpMethod.Delete to "/splitwise/imports/$id")) {
            assertEquals(HttpStatusCode.Unauthorized, call(m, p, user = null).status, "$m $p")
        }
    }

    @Test
    fun `a forged token is refused`() = routeTest {
        val res = client.get("/api/v1/splitwise/entries") { cookie(ACCESS_TOKEN_COOKIE, jwt.generateAccessToken(owner).dropLast(3) + "abc") }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `another user's entries and imports are 404, and their lists are empty`() = routeTest {
        val importId = data(upload(csv).bodyAsText()).jsonObject["importId"]!!.jsonPrimitive.content
        val entry = runBlocking { repo.findByUser(owner.id) }.first { it.state == SplitState.UNMATCHED }
        for (action in listOf("confirm", "reject", "ignore", "undo")) {
            val res = call(HttpMethod.Post, "/splitwise/entries/${entry.id}/$action", user = stranger)
            assertEquals(HttpStatusCode.NotFound, res.status, action)
            assertTrue("SPLIT_001" in res.bodyAsText())
        }
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, "/splitwise/imports/$importId", user = stranger).status)
        assertEquals(0, data(call(HttpMethod.Get, "/splitwise/entries", user = stranger).bodyAsText()).jsonArray.size)
        assertEquals(0, data(call(HttpMethod.Get, "/splitwise/imports", user = stranger).bodyAsText()).jsonArray.size)
        assertTrue(runBlocking { repo.findById(entry.id) }?.state == SplitState.UNMATCHED, "nothing changed")
    }

    @Test
    fun `the owner can confirm by picking a transaction, undo, and undo the import`() = routeTest {
        val importId = data(upload(csv).bodyAsText()).jsonObject["importId"]!!.jsonPrimitive.content
        val entry = runBlocking { repo.findByUser(owner.id) }.first { it.state == SplitState.UNMATCHED }
        val debit = runBlocking {
            txns.save(Transaction(userId = owner.id, sourceType = "statement", type = TransactionType.DEBIT, amount = BigDecimal("4000.00"),
                transactedAt = LocalDate.of(2026, 10, 3).atStartOfDay(ZoneId.of("Asia/Kolkata")).plusHours(20).toInstant()))
        }
        assertEquals(HttpStatusCode.Conflict, call(HttpMethod.Post, "/splitwise/entries/${entry.id}/confirm").status, "nothing suggested")
        val ok = call(HttpMethod.Post, "/splitwise/entries/${entry.id}/confirm", json = """{"transactionId":"${debit.id}"}""")
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        assertEquals(BigDecimal("2000.00"), runBlocking { txns.findById(debit.id) }?.ownShare)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/splitwise/entries/${entry.id}/undo").status)
        assertEquals(null, runBlocking { txns.findById(debit.id) }?.ownShare)
        assertEquals(HttpStatusCode.Conflict, call(HttpMethod.Post, "/splitwise/entries/${entry.id}/reject").status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/splitwise/entries/${entry.id}/ignore").status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Delete, "/splitwise/imports/$importId").status)
        assertEquals(0, data(call(HttpMethod.Get, "/splitwise/entries").bodyAsText()).jsonArray.size)
    }

    @Test
    fun `bad ids, bodies and params are 400`() = routeTest {
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/splitwise/entries/not-a-uuid/undo").status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Delete, "/splitwise/imports/%2e%2e%2fetc").status)
        val id = UUID.randomUUID()
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/splitwise/entries/$id/confirm", json = """{"transactionId":"x' OR 1=1"}""").status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/splitwise/entries/$id/confirm", json = """{"transactionId":""").status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Get, "/splitwise/entries?state=deleted").status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Get, "/splitwise/entries?limit=501").status)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/splitwise/entries/$id/undo").status)
    }

    @Test
    fun `uploads without a file or a name, or with a file that isn't an export, are refused`() = routeTest {
        assertEquals(HttpStatusCode.BadRequest, upload(null).status)
        assertEquals(HttpStatusCode.BadRequest, upload(csv, me = null).status)
        assertEquals(HttpStatusCode.BadRequest, upload(csv, me = "x".repeat(201)).status)
        val unknown = upload(csv, me = "Nobody")
        assertEquals(HttpStatusCode.UnprocessableEntity, unknown.status)
        assertTrue("SPLITWISE_ME_UNKNOWN" in unknown.bodyAsText() && "Asha" !in unknown.bodyAsText())
        assertEquals(HttpStatusCode.UnprocessableEntity, upload("%PDF-1.7\u0000".toByteArray()).status)
        val big = upload(ByteArray(SplitwiseCsv.MAX_BYTES + 1) { 'a'.code.toByte() })
        assertEquals(HttpStatusCode.PayloadTooLarge, big.status)
        assertTrue("FILE_TOO_LARGE" in big.bodyAsText())
    }
}
