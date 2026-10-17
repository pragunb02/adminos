package dev.adminos.api.domain.financial.learning

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.financial.InMemoryCategoryRuleRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.learning.LearningFixtures.CHAI
import dev.adminos.api.domain.financial.learning.LearningFixtures.KIRANAS
import dev.adminos.api.domain.financial.learning.LearningFixtures.PHARMACIES
import dev.adminos.api.domain.financial.learning.LearningFixtures.TUTORS
import dev.adminos.api.domain.financial.learning.LearningFixtures.clock
import dev.adminos.api.domain.financial.learning.LearningFixtures.txn
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.infrastructure.plugins.ACCESS_TOKEN_COOKIE
import dev.adminos.api.infrastructure.plugins.RateLimiter
import dev.adminos.api.infrastructure.plugins.configureAuthentication
import dev.adminos.api.infrastructure.plugins.configureErrorHandling
import dev.adminos.api.infrastructure.plugins.configureRequestId
import dev.adminos.api.infrastructure.plugins.configureSerialization
import io.ktor.client.request.cookie
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LearnedCategoryRoutesTest {
    private val jwt = JwtService(AppConfig.load().auth)
    private val me = User(id = UUID.randomUUID(), email = "me@example.com", googleId = "g-707", role = UserRole.OWNER)
    private val txns = InMemoryTransactionRepository()

    private fun service(enabled: Boolean): CategoryModelTrainingService {
        val store = CategoryModelStore(InMemoryCategoryModelRepository(), clock)
        return CategoryModelTrainingService(txns, store, LearnedCategorizer(store, InMemoryCategoryRuleRepository(), txns, clock = clock),
            emptyList(), enabled, clock = clock)
    }

    private fun app(enabled: Boolean = true, limiter: RateLimiter = RateLimiter(10, 3600), block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwt)
            routing { route("/api/v1") { learnedCategoryRoutes(service(enabled), limiter) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, path: String, signedIn: Boolean = true) =
        client.request("/api/v1/categories/model$path") {
            this.method = method
            if (signedIn) cookie(ACCESS_TOKEN_COOKIE, jwt.generateAccessToken(me))
        }

    private fun data(body: String) = Json.parseToJsonElement(body).jsonObject.getValue("data").jsonObject

    private fun seedCorrections() = runBlocking {
        listOf(PHARMACIES to TransactionCategory.HEALTH, KIRANAS to TransactionCategory.SHOPPING, TUTORS to TransactionCategory.EDUCATION,
            CHAI to TransactionCategory.FOOD).forEach { (names, c) -> names.forEach { txns.save(txn(me.id, it, c, "user")) } }
        txns.save(txn(me.id, "Mahadev Medicos"))
    }

    @Test
    fun `the status says off when learning is switched off, and retraining is refused`() = app(enabled = false) {
        val status = call(HttpMethod.Get, "")
        assertEquals(HttpStatusCode.OK, status.status)
        val d = data(status.bodyAsText())
        assertEquals("false", d.getValue("enabled").jsonPrimitive.content); assertEquals("off", d.getValue("state").jsonPrimitive.content)
        val train = call(HttpMethod.Post, "/train")
        assertEquals(HttpStatusCode.Conflict, train.status)
        assertTrue("LEARN_001" in train.bodyAsText())
    }

    @Test
    fun `before enough corrections the status counts what is still needed`() = app {
        val d = data(call(HttpMethod.Get, "").bodyAsText())
        assertEquals("cold_start", d.getValue("state").jsonPrimitive.content)
        assertEquals(20, d.getValue("correctionsNeeded").jsonPrimitive.int)
        val train = data(call(HttpMethod.Post, "/train").bodyAsText())
        assertEquals("cold_start", train.getValue("outcome").jsonPrimitive.content)
        assertEquals("too_few_corrections", train.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun `retraining now trains, re-scores recent rows and returns the new status`() = app {
        seedCorrections()
        val response = call(HttpMethod.Post, "/train")
        assertEquals(HttpStatusCode.OK, response.status)
        val d = data(response.bodyAsText())
        assertEquals("trained", d.getValue("outcome").jsonPrimitive.content)
        assertEquals(1, d.getValue("applied").jsonPrimitive.int + d.getValue("suggested").jsonPrimitive.int)
        val status = d.getValue("status").jsonObject
        assertEquals("ready", status.getValue("state").jsonPrimitive.content)
        assertEquals("apply", status.getValue("mode").jsonPrimitive.content)
        assertEquals(40, status.getValue("trainingRows").jsonPrimitive.int)
        assertTrue("Medicos" !in response.bodyAsText(), "no payee names in the response")
    }

    @Test
    fun `retraining too often is refused with a retry time`() = app(limiter = RateLimiter(1, 3600)) {
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/train").status)
        val second = call(HttpMethod.Post, "/train")
        assertEquals(HttpStatusCode.TooManyRequests, second.status)
        assertTrue("RATE_001" in second.bodyAsText())
    }

    @Test
    fun `both routes need a session`() = app {
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "", signedIn = false).status)
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Post, "/train", signedIn = false).status)
    }

    @Test
    fun `the train response maps every outcome`() {
        val status = CategoryModelStatusResponse.from(false, ModelStatus(ModelState.OFF, 0, 20))
        assertEquals("off", trainResponse(RetrainReport(TrainingOutcome.Disabled), status).outcome)
    }
}
