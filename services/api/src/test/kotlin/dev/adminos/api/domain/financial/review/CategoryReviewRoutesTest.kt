package dev.adminos.api.domain.financial.review

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.InMemoryAuditRepository
import dev.adminos.api.domain.financial.CategoryCorrectionService
import dev.adminos.api.domain.financial.InMemoryCategoryRuleRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.learning.LearningFixtures.clock
import dev.adminos.api.domain.financial.learning.LearningFixtures.txn
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.infrastructure.plugins.ACCESS_TOKEN_COOKIE
import dev.adminos.api.infrastructure.plugins.configureAuthentication
import dev.adminos.api.infrastructure.plugins.configureErrorHandling
import dev.adminos.api.infrastructure.plugins.configureRequestId
import dev.adminos.api.infrastructure.plugins.configureSerialization
import io.ktor.client.request.cookie
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CategoryReviewRoutesTest {
    private val jwt = JwtService(AppConfig.load().auth)
    private val me = User(id = UUID.randomUUID(), email = "me@example.com", googleId = "g-708", role = UserRole.OWNER)
    private val txns = InMemoryTransactionRepository()
    private val review = CategoryReviewService(txns, CategoryCorrectionService(InMemoryCategoryRuleRepository(), txns), InMemoryGoldenCandidateRepository(),
        AuditService(InMemoryAuditRepository(), CoroutineScope(Dispatchers.Unconfined)), clock = clock)

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwt)
            routing { route("/api/v1") { categoryReviewRoutes(review) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, path: String, body: String? = null, signedIn: Boolean = true): HttpResponse =
        client.request("/api/v1/categories$path") {
            this.method = method
            if (signedIn) cookie(ACCESS_TOKEN_COOKIE, jwt.generateAccessToken(me))
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }

    private fun json(body: String) = Json.parseToJsonElement(body).jsonObject

    private fun seed(name: String = "Sai Medicos", amount: String = "240.00"): Transaction = runBlocking {
        txns.save(txn(me.id, name, metadata = mapOf("learned" to mapOf("status" to "suggested", "category" to "health", "confidence" to 0.55,
            "cues" to listOf("medicos"), "alternatives" to listOf("shopping"), "model" to "softmax-v1@abc"))).copy(amount = BigDecimal(amount)))
    }

    @Test
    fun `the queue lists questions with their guess and priority`() = app {
        val t = seed()
        val response = call(HttpMethod.Get, "/review")
        assertEquals(HttpStatusCode.OK, response.status)
        val item = json(response.bodyAsText()).getValue("data").jsonArray.single().jsonObject
        assertEquals(t.id.toString(), item.getValue("transaction").jsonObject.getValue("id").jsonPrimitive.content)
        val guess = item.getValue("guess").jsonObject
        assertEquals("health", guess.getValue("category").jsonPrimitive.content)
        assertEquals("learned", guess.getValue("by").jsonPrimitive.content)
        assertEquals("false", guess.getValue("applied").jsonPrimitive.content)
        assertEquals("[\"shopping\"]", guess.getValue("alternatives").toString())
        assertEquals("108.0", item.getValue("priority").jsonPrimitive.content)
        assertEquals("240.00", item.getValue("transaction").jsonObject.getValue("amount").jsonPrimitive.content)
    }

    @Test
    fun `a bad limit is a 400`() = app {
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Get, "/review?limit=0").status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Get, "/review?limit=51").status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/review?limit=50").status)
    }

    @Test
    fun `answering confirms or corrects and can keep a scrubbed copy`() = app {
        val t = seed()
        val response = call(HttpMethod.Post, "/review/${t.id}", """{"category":"health","addToGolden":true}""")
        assertEquals(HttpStatusCode.OK, response.status)
        val data = json(response.bodyAsText()).getValue("data").jsonObject
        assertEquals("user", data.getValue("transaction").jsonObject.getValue("categorySource").jsonPrimitive.content)
        assertEquals("true", data.getValue("remembered").jsonPrimitive.content)
        val golden = data.getValue("golden").jsonObject
        assertEquals("added", golden.getValue("outcome").jsonPrimitive.content)
        assertEquals("UPI-SAI MEDICOS-PAYMENT", golden.getValue("row").jsonObject.getValue("merchantRaw").jsonPrimitive.content)
        assertEquals("[]", json(call(HttpMethod.Get, "/review").bodyAsText()).getValue("data").toString())

        val again = json(call(HttpMethod.Post, "/review/${t.id}", """{"category":"HEALTH","addToGolden":true}""").bodyAsText())
        assertEquals("already_there", again.getValue("data").jsonObject.getValue("golden").jsonObject.getValue("outcome").jsonPrimitive.content)
        val other = json(call(HttpMethod.Post, "/review/${t.id}", """{"category":"other","addToGolden":true}""").bodyAsText())
        val refused = other.getValue("data").jsonObject.getValue("golden").jsonObject
        assertEquals("refused", refused.getValue("outcome").jsonPrimitive.content)
        assertEquals("looks_like_a_person", refused.getValue("reason").jsonPrimitive.content)
        val plain = json(call(HttpMethod.Post, "/review/${t.id}", """{"category":"food"}""").bodyAsText())
        assertTrue(plain.getValue("data").jsonObject["golden"].let { it == null || it.toString() == "null" })
    }

    @Test
    fun `bad answers are 400 and unknown transactions 404`() = app {
        val t = seed()
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/review/${t.id}", """{"category":"rent"}""").status)
        assertTrue("VALIDATION_003" in call(HttpMethod.Post, "/review/${t.id}", """{"category":"rent"}""").bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/review/not-a-uuid", """{"category":"food"}""").status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/review/${t.id}", """{"addToGolden":true}""").status)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/review/${UUID.randomUUID()}", """{"category":"food"}""").status)
    }

    @Test
    fun `the local golden set lists, downloads as CSV and deletes`() = app {
        val t = seed()
        call(HttpMethod.Post, "/review/${t.id}", """{"category":"health","addToGolden":true}""")
        val rows = json(call(HttpMethod.Get, "/golden").bodyAsText()).getValue("data").jsonArray
        val row = rows.single().jsonObject
        assertTrue(row.getValue("goldenId").jsonPrimitive.content.startsWith("L"))

        val csv = call(HttpMethod.Get, "/golden.csv")
        assertEquals(HttpStatusCode.OK, csv.status)
        assertTrue(csv.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/csv"))
        assertTrue(csv.headers[HttpHeaders.ContentDisposition].orEmpty().contains("golden.local.csv"))
        assertTrue(csv.bodyAsText().contains(",UPI-SAI MEDICOS-PAYMENT,,UPI,DEBIT,HEALTH,,real-shaped local"))

        val id = row.getValue("id").jsonPrimitive.content
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Delete, "/golden/$id").status)
        val gone = call(HttpMethod.Delete, "/golden/$id")
        assertEquals(HttpStatusCode.NotFound, gone.status)
        assertTrue("REVIEW_001" in gone.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Delete, "/golden/x").status)
    }

    @Test
    fun `every route needs a session`() = app {
        for ((method, path) in listOf(HttpMethod.Get to "/review", HttpMethod.Post to "/review/${UUID.randomUUID()}", HttpMethod.Get to "/golden",
            HttpMethod.Get to "/golden.csv", HttpMethod.Delete to "/golden/${UUID.randomUUID()}")) {
            assertEquals(HttpStatusCode.Unauthorized, call(method, path, body = if (method == HttpMethod.Post) "{}" else null, signedIn = false).status, path)
        }
    }
}
