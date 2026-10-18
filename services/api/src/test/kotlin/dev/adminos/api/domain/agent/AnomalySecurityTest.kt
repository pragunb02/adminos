package dev.adminos.api.domain.agent

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.agent.baselines.MedianMadBaseline
import dev.adminos.api.domain.agent.baselines.ToleranceLevel
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.InMemoryAuditRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.Transaction
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AOS-713 security review, as tests: nobody but the owner of an alert can move its merchant's tolerance,
 * hostile merchant names and amounts are handled as plain data, and errors fail closed.
 */
class AnomalySecurityTest {
    private val config = AppConfig.load().auth
    private val jwt = JwtService(config)
    private val user = User(id = UUID.randomUUID(), email = "owner@example.com", googleId = "g-owner", role = UserRole.OWNER)
    private val other = UUID.randomUUID()
    private val anomalies = InMemoryAnomalyRepository()
    private val tolerances = InMemoryAnomalyToleranceRepository()
    private val service = AnomalyReviewService(anomalies, InMemoryTransactionRepository(), tolerances, AuditService(InMemoryAuditRepository(), CoroutineScope(Dispatchers.Unconfined)))

    private fun routes(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwt)
            routing { route("/api/v1") { anomalyRoutes(service) } }
        }
        block()
    }

    private suspend fun amountAlert(owner: UUID = user.id) = anomalies.save(Anomaly(userId = owner, transactionId = UUID.randomUUID(),
        type = AnomalyType.LARGE_AMOUNT, confidenceScore = 0.7, reason = "r", rulesFired = setOf("large_amount"), merchantKey = "swiggy"))

    private suspend fun ApplicationTestBuilder.patch(id: UUID, token: String?, body: String = """{"status":"dismissed"}""") =
        client.patch("/api/v1/anomalies/$id") {
            token?.let { cookie(ACCESS_TOKEN_COOKIE, it) }
            contentType(ContentType.Application.Json); setBody(body)
        }.status

    @Test
    fun `without a session, or with a forged one, an answer is refused and teaches nothing`() = routes {
        val a = amountAlert()
        assertEquals(HttpStatusCode.Unauthorized, patch(a.id, token = null))
        val forged = JwtService(config.copy(jwtSecret = "an-attacker-secret-that-is-32-bytes-long!!")).generateAccessToken(user)
        assertEquals(HttpStatusCode.Unauthorized, patch(a.id, token = forged))
        assertEquals(AnomalyStatus.OPEN, anomalies.findById(a.id)?.status)
        assertNull(tolerances.find(user.id, "swiggy", TolerableRule.LARGE_AMOUNT))
    }

    @Test
    fun `someone else's alert is 404 and their merchant's tolerance can't be moved`() = routes {
        val theirs = amountAlert(owner = other)
        assertEquals(HttpStatusCode.NotFound, patch(theirs.id, jwt.generateAccessToken(user)))
        assertNull(tolerances.find(other, "swiggy", TolerableRule.LARGE_AMOUNT))
        assertNull(tolerances.find(user.id, "swiggy", TolerableRule.LARGE_AMOUNT))
    }

    @Test
    fun `hostile bodies are 400 and change nothing`() = routes {
        val a = amountAlert()
        val token = jwt.generateAccessToken(user)
        for (body in listOf("""{"status":"${"x".repeat(10_000)}"}""", """{"status":"dismissed'; DROP TABLE anomalies;--"}""", "not json", """{"status":null}""")) {
            assertEquals(HttpStatusCode.BadRequest, patch(a.id, token, body), body.take(40))
        }
        assertEquals(AnomalyStatus.OPEN, anomalies.findById(a.id)?.status)
        assertNull(tolerances.find(user.id, "swiggy", TolerableRule.LARGE_AMOUNT))
    }

    @Test
    fun `a merchant name with markup or control characters stays plain text in the alert`() {
        val now = Instant.parse("2026-03-10T06:30:00Z")
        val name = "<img src=x onerror=alert(1)>‮"
        val history = (1..5).map {
            Transaction(userId = user.id, type = TransactionType.DEBIT, amount = BigDecimal("400.00"), merchantName = name,
                category = TransactionCategory.FOOD, transactedAt = now.minus(it.toLong(), ChronoUnit.DAYS))
        }
        val subject = history.first().copy(id = UUID.randomUUID(), amount = BigDecimal("4000.00"), transactedAt = now)
        val result = assertNotNull(RobustAmountRule().evaluate(subject, AmountContext(history, ZoneId.of("Asia/Kolkata"))))
        assertTrue(result.reason.contains("at <img src=x onerror=alert(1)>"), "kept verbatim: the web renders it as text, never as HTML")
    }

    @Test
    fun `absurd amounts neither overflow nor divide by zero`() {
        val b = assertNotNull(MedianMadBaseline().fit(List(5) { BigDecimal("1000000000000.00") }))
        assertTrue(b.isUnusual(BigDecimal("99999999999999.99"), ToleranceLevel.DEFAULT))
        val tiny = assertNotNull(MedianMadBaseline().fit(List(5) { BigDecimal("0.01") }))
        assertTrue(tiny.distance(BigDecimal("0.01")).isFinite())
    }

    @Test
    fun `an error while learning leaves the alert unresolved, so nothing half-happens`() = runBlocking<Unit> {
        val a = amountAlert()
        val failing = object : AnomalyToleranceRepository by tolerances {
            override suspend fun record(userId: UUID, merchantKey: String, rule: TolerableRule, feedback: AlertFeedback, at: Instant): AnomalyTolerance =
                throw java.io.IOException("db down")
        }
        // In production the unit of work is one database transaction; here it is the same rollback, by hand.
        val rollingBack = AnomalyReviewService(anomalies, InMemoryTransactionRepository(), failing, AuditService(InMemoryAuditRepository(), CoroutineScope(Dispatchers.Unconfined))) { work ->
            val before = anomalies.findById(a.id)
            try { work() } catch (e: java.io.IOException) { before?.let { anomalies.update(it) }; throw e }
        }
        kotlin.test.assertFailsWith<java.io.IOException> { rollingBack.resolve(user.id, a.id, AnomalyStatus.DISMISSED) }
        assertEquals(AnomalyStatus.OPEN, anomalies.findById(a.id)?.status)
    }
}
