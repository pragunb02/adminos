package dev.adminos.api.domain.agent

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.audit.AuditActions
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.InMemoryAuditRepository
import dev.adminos.api.domain.common.CursorCodec
import dev.adminos.api.domain.financial.BillingCycle
import dev.adminos.api.domain.financial.InMemorySubscriptionRepository
import dev.adminos.api.domain.financial.Subscription
import dev.adminos.api.domain.financial.SubscriptionRepository
import dev.adminos.api.domain.financial.UsageStatus
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Route coverage for /anomalies, /briefings and /subscriptions agent actions (AOS-312). */
class AgentRoutesCoverageTest {
    private val jwtService = JwtService(AppConfig.load().auth)
    private val user = User(id = UUID.randomUUID(), email = "pragun@example.in", googleId = "g-agent-1", role = UserRole.OWNER)
    private val otherUserId = UUID.randomUUID()
    private val jwt = jwtService.generateAccessToken(user)

    private val anomalies = InMemoryAnomalyRepository()
    private val briefings = InMemoryBriefingRepository()
    private val insights = InMemoryInsightRepository()
    private val subscriptions = InMemorySubscriptionRepository()
    private val auditRepo = InMemoryAuditRepository()
    // Unconfined: the fire-and-forget audit write runs synchronously inside log(), so tests can assert it.
    private val auditService = AuditService(auditRepo, CoroutineScope(Dispatchers.Unconfined))

    private val t0 = Instant.parse("2026-10-10T06:30:00Z")

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            routing {
                route("/api/v1") {
                    anomalyRoutes(anomalies, auditService)
                    briefingRoutes(briefings, insights)
                    subscriptionAgentRoutes(subscriptions, auditService)
                }
            }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.call(
        method: HttpMethod, path: String, body: String? = null
    ): Pair<HttpStatusCode, JsonObject> {
        val res = client.request(path) {
            this.method = method
            cookie(ACCESS_TOKEN_COOKIE, jwt)
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        return res.status to Json.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    private fun JsonObject.errorCode() = this["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
    private fun JsonElement.str(key: String) = jsonObject[key]!!.jsonPrimitive.content

    private fun anomaly(
        userId: UUID = user.id, status: AnomalyStatus = AnomalyStatus.OPEN, createdAt: Instant = t0
    ) = Anomaly(
        userId = userId, transactionId = UUID.randomUUID(), type = AnomalyType.CARD_TESTING,
        confidenceScore = 0.9, reason = "Amount of exactly ₹1.00 matches card testing pattern",
        status = status, createdAt = createdAt
    )

    private fun briefing(userId: UUID = user.id, createdAt: Instant = t0) = Briefing(
        userId = userId, periodStart = LocalDate.of(2026, 10, 5), periodEnd = LocalDate.of(2026, 10, 11),
        content = "You spent ₹12,450 this week; Swiggy was your top merchant.",
        totalSpent = BigDecimal("12450.00"), totalIncome = BigDecimal("85000.00"),
        topCategories = listOf(CategoryAmount("food", BigDecimal("4200.50"))),
        modelUsed = "claude", promptVersion = "v1", tokensUsed = 900, createdAt = createdAt
    )

    private fun subscription(userId: UUID = user.id) = Subscription(
        userId = userId, name = "Netflix", merchantName = "NETFLIX.COM", amount = BigDecimal("649.00"),
        billingCycle = BillingCycle.MONTHLY, usageStatus = UsageStatus.UNUSED, isFlagged = true
    )

    // ── Auth ──

    @Test
    fun `agent routes reject a request without a signed-in user`() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/anomalies").status)
    }

    // ── GET /anomalies ──

    @Test
    fun `listing anomalies returns only the caller's anomalies newest first`() = routeTest {
        anomalies.save(anomaly(createdAt = t0))
        anomalies.save(anomaly(createdAt = t0.plusSeconds(60)))
        anomalies.save(anomaly(userId = otherUserId))

        val (status, body) = call(HttpMethod.Get, "/api/v1/anomalies")

        assertEquals(HttpStatusCode.OK, status)
        val data = body["data"]!!.jsonArray
        assertEquals(2, data.size)
        assertEquals(t0.plusSeconds(60).toString(), data[0].str("createdAt"))
        val pagination = body["pagination"]!!.jsonObject
        assertEquals("false", pagination["hasMore"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, pagination["cursor"])
        assertEquals("2", pagination["total"]!!.jsonPrimitive.content)
    }

    @Test
    fun `listing anomalies filters by status`() = routeTest {
        anomalies.save(anomaly(status = AnomalyStatus.OPEN))
        anomalies.save(anomaly(status = AnomalyStatus.DISMISSED, createdAt = t0.plusSeconds(1)))

        val (_, body) = call(HttpMethod.Get, "/api/v1/anomalies?status=dismissed")

        val data = body["data"]!!.jsonArray
        assertEquals(listOf("dismissed"), data.map { it.str("status") })
        assertEquals("1", body["pagination"]!!.jsonObject["total"]!!.jsonPrimitive.content)
    }

    @Test
    fun `listing anomalies pages with a cursor when more rows exist than the limit`() = routeTest {
        anomalies.save(anomaly(createdAt = t0))
        anomalies.save(anomaly(createdAt = t0.plusSeconds(60)))

        val (_, first) = call(HttpMethod.Get, "/api/v1/anomalies?limit=1")
        val pagination = first["pagination"]!!.jsonObject
        assertEquals("true", pagination["hasMore"]!!.jsonPrimitive.content)
        val cursor = pagination["cursor"]!!.jsonPrimitive.content
        assertEquals(t0.plusSeconds(60).toString(), CursorCodec.key(cursor))

        val (_, second) = call(HttpMethod.Get, "/api/v1/anomalies?limit=1&cursor=$cursor")
        assertEquals(listOf(t0.toString()), second["data"]!!.jsonArray.map { it.str("createdAt") })
        assertEquals("false", second["pagination"]!!.jsonObject["hasMore"]!!.jsonPrimitive.content)
    }

    @Test
    fun `listing anomalies with an unknown status is a 400`() = routeTest {
        val (status, _) = call(HttpMethod.Get, "/api/v1/anomalies?status=pending")
        assertEquals(HttpStatusCode.BadRequest, status)
    }

    @Test
    fun `listing anomalies with a malformed cursor is a 400`() = routeTest {
        val (status, _) = call(HttpMethod.Get, "/api/v1/anomalies?cursor=not-a-cursor")
        assertEquals(HttpStatusCode.BadRequest, status)
    }

    @Test
    fun `listing anomalies with a cursor whose key is not an instant is a 400`() = routeTest {
        val cursor = CursorCodec.encode("yesterday")
        val (status, _) = call(HttpMethod.Get, "/api/v1/anomalies?cursor=$cursor")
        assertEquals(HttpStatusCode.BadRequest, status)
    }

    // ── GET /anomalies/{id} ──

    @Test
    fun `getting an anomaly by id returns it`() = routeTest {
        val a = anomalies.save(anomaly())

        val (status, body) = call(HttpMethod.Get, "/api/v1/anomalies/${a.id}")

        assertEquals(HttpStatusCode.OK, status)
        val data = body["data"]!!.jsonObject
        assertEquals(a.id.toString(), data.str("id"))
        assertEquals("card_testing", data.str("type"))
        assertEquals("open", data.str("status"))
    }

    @Test
    fun `getting an anomaly with a non-uuid id is a 400`() = routeTest {
        val (status, body) = call(HttpMethod.Get, "/api/v1/anomalies/abc")
        assertEquals(HttpStatusCode.BadRequest, status)
        assertEquals("ANOM_001", body.errorCode())
    }

    @Test
    fun `getting an unknown anomaly is a 404`() = routeTest {
        val (status, body) = call(HttpMethod.Get, "/api/v1/anomalies/${UUID.randomUUID()}")
        assertEquals(HttpStatusCode.NotFound, status)
        assertEquals("ANOM_001", body.errorCode())
    }

    @Test
    fun `getting another user's anomaly is a 404`() = routeTest {
        val a = anomalies.save(anomaly(userId = otherUserId))
        val (status, _) = call(HttpMethod.Get, "/api/v1/anomalies/${a.id}")
        assertEquals(HttpStatusCode.NotFound, status)
    }

    // ── PATCH /anomalies/{id} ──

    @Test
    fun `confirming an anomaly safe resolves it by the user and audits the change`() = routeTest {
        val a = anomalies.save(anomaly())

        val (status, body) = call(HttpMethod.Patch, "/api/v1/anomalies/${a.id}", """{"status":"confirmed_safe"}""")

        assertEquals(HttpStatusCode.OK, status)
        val data = body["data"]!!.jsonObject
        assertEquals("confirmed_safe", data.str("status"))
        assertEquals("user", data.str("resolvedBy"))
        assertNotNull(Instant.parse(data.str("resolvedAt")))
        val log = auditRepo.findByEntity("anomaly", a.id).single()
        assertEquals(AuditActions.ANOMALY_CONFIRM_SAFE, log.action)
        assertEquals("open", log.beforeState!!["status"]!!.jsonPrimitive.content)
        assertEquals("confirmed_safe", log.afterState!!["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `confirming an anomaly as fraud audits the fraud action`() = routeTest {
        val a = anomalies.save(anomaly())

        val (status, _) = call(HttpMethod.Patch, "/api/v1/anomalies/${a.id}", """{"status":"confirmed_fraud"}""")

        assertEquals(HttpStatusCode.OK, status)
        assertEquals(AnomalyStatus.CONFIRMED_FRAUD, anomalies.findById(a.id)!!.status)
        assertEquals(AuditActions.ANOMALY_CONFIRM_FRAUD, auditRepo.findByEntity("anomaly", a.id).single().action)
    }

    @Test
    fun `dismissing an anomaly audits the dismiss action`() = routeTest {
        val a = anomalies.save(anomaly())

        val (status, _) = call(HttpMethod.Patch, "/api/v1/anomalies/${a.id}", """{"status":"dismissed"}""")

        assertEquals(HttpStatusCode.OK, status)
        assertEquals(AnomalyStatus.DISMISSED, anomalies.findById(a.id)!!.status)
        assertEquals("anomaly.dismiss", auditRepo.findByEntity("anomaly", a.id).single().action)
    }

    @Test
    fun `resolving an anomaly with an unsupported status is a 400 ANOM_002`() = routeTest {
        val a = anomalies.save(anomaly())

        val (status, body) = call(HttpMethod.Patch, "/api/v1/anomalies/${a.id}", """{"status":"open"}""")

        assertEquals(HttpStatusCode.BadRequest, status)
        assertEquals("ANOM_002", body.errorCode())
        assertEquals(AnomalyStatus.OPEN, anomalies.findById(a.id)!!.status)
    }

    @Test
    fun `resolving an anomaly without a status in the body is a 400 malformed body`() = routeTest {
        val a = anomalies.save(anomaly())

        val (status, body) = call(HttpMethod.Patch, "/api/v1/anomalies/${a.id}", "{}")

        assertEquals(HttpStatusCode.BadRequest, status)
        assertEquals("VALIDATION_004", body.errorCode())
    }

    @Test
    fun `resolving an anomaly with a non-uuid id is a 400`() = routeTest {
        val (status, body) = call(HttpMethod.Patch, "/api/v1/anomalies/xyz", """{"status":"dismissed"}""")
        assertEquals(HttpStatusCode.BadRequest, status)
        assertEquals("ANOM_001", body.errorCode())
    }

    @Test
    fun `resolving an unknown anomaly is a 404`() = routeTest {
        val (status, _) = call(HttpMethod.Patch, "/api/v1/anomalies/${UUID.randomUUID()}", """{"status":"dismissed"}""")
        assertEquals(HttpStatusCode.NotFound, status)
    }

    @Test
    fun `resolving another user's anomaly is a 404 and leaves it open`() = routeTest {
        val a = anomalies.save(anomaly(userId = otherUserId))

        val (status, _) = call(HttpMethod.Patch, "/api/v1/anomalies/${a.id}", """{"status":"dismissed"}""")

        assertEquals(HttpStatusCode.NotFound, status)
        assertEquals(AnomalyStatus.OPEN, anomalies.findById(a.id)!!.status)
    }

    // ── /briefings ──

    @Test
    fun `latest briefing is null when the user has none`() = routeTest {
        val (status, body) = call(HttpMethod.Get, "/api/v1/briefings/latest")
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(JsonNull, body["data"])
    }

    @Test
    fun `latest briefing includes its insights`() = routeTest {
        briefings.save(briefing(createdAt = t0))
        val latest = briefings.save(briefing(createdAt = t0.plusSeconds(3600)))
        insights.save(Insight(userId = user.id, briefingId = latest.id, type = InsightType.SUBSCRIPTION_WASTE,
            title = "Unused Netflix", body = "No logins in 45 days", severity = Severity.WARNING,
            actionType = InsightActionType.CANCEL_SUB))

        val (status, body) = call(HttpMethod.Get, "/api/v1/briefings/latest")

        assertEquals(HttpStatusCode.OK, status)
        val data = body["data"]!!.jsonObject
        assertEquals(latest.id.toString(), data.str("id"))
        assertEquals(listOf("Unused Netflix"), data["insights"]!!.jsonArray.map { it.str("title") })
    }

    @Test
    fun `briefing history pages with a cursor`() = routeTest {
        briefings.save(briefing(createdAt = t0))
        briefings.save(briefing(createdAt = t0.plusSeconds(60)))
        briefings.save(briefing(userId = otherUserId))

        val (status, first) = call(HttpMethod.Get, "/api/v1/briefings?limit=1")
        assertEquals(HttpStatusCode.OK, status)
        val pagination = first["pagination"]!!.jsonObject
        assertEquals("true", pagination["hasMore"]!!.jsonPrimitive.content)
        assertEquals("2", pagination["total"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, first["data"]!!.jsonArray[0].jsonObject["insights"])
        val cursor = pagination["cursor"]!!.jsonPrimitive.content

        val (_, second) = call(HttpMethod.Get, "/api/v1/briefings?limit=1&cursor=$cursor")
        assertEquals(listOf(t0.toString()), second["data"]!!.jsonArray.map { it.str("createdAt") })
        assertEquals("false", second["pagination"]!!.jsonObject["hasMore"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, second["pagination"]!!.jsonObject["cursor"])
    }

    // ── PATCH /subscriptions/{id}/dismiss ──

    @Test
    fun `dismissing a subscription flag marks it active and audits a keep`() = routeTest {
        val sub = subscriptions.save(subscription())

        val (status, body) = call(HttpMethod.Patch, "/api/v1/subscriptions/${sub.id}/dismiss", "{}")

        assertEquals(HttpStatusCode.OK, status)
        assertEquals(sub.id.toString(), body["data"]!!.str("id"))
        val stored = subscriptions.findById(sub.id)!!
        assertFalse(stored.isFlagged)
        assertEquals(UsageStatus.ACTIVE, stored.usageStatus)
        assertNotNull(stored.flagDismissedAt)
        val log = auditRepo.findByEntity("subscription", sub.id).single()
        assertEquals(AuditActions.SUBSCRIPTION_KEEP, log.action)
        assertEquals(stored.flagDismissedAt.toString(), log.afterState!!["flag_dismissed_at"]!!.jsonPrimitive.content)
    }

    @Test
    fun `keeping a subscription remembers the waste factors the user saw (AOS-509)`() = routeTest {
        val sub = subscriptions.save(subscription().copy(wasteReasons = listOf(
            dev.adminos.api.domain.financial.WasteReason(dev.adminos.api.domain.financial.WasteFactor.PRICE_INCREASED, 30, "Price went up 25% at the last charge"),
            dev.adminos.api.domain.financial.WasteReason(dev.adminos.api.domain.financial.WasteFactor.MARKED_UNUSED, 40, "Marked as not used"))))

        val (status, _) = call(HttpMethod.Patch, "/api/v1/subscriptions/${sub.id}/dismiss", "{}")

        assertEquals(HttpStatusCode.OK, status)
        assertEquals(setOf(dev.adminos.api.domain.financial.WasteFactor.PRICE_INCREASED, dev.adminos.api.domain.financial.WasteFactor.MARKED_UNUSED),
            subscriptions.findById(sub.id)!!.wasteFactorsDismissed)
    }

    @Test
    fun `dismissing a subscription with a non-uuid id is a 400`() = routeTest {
        val (status, body) = call(HttpMethod.Patch, "/api/v1/subscriptions/nope/dismiss", "{}")
        assertEquals(HttpStatusCode.BadRequest, status)
        assertEquals("SUB_001", body.errorCode())
    }

    @Test
    fun `dismissing an unknown subscription is a 404`() = routeTest {
        val (status, _) = call(HttpMethod.Patch, "/api/v1/subscriptions/${UUID.randomUUID()}/dismiss", "{}")
        assertEquals(HttpStatusCode.NotFound, status)
    }

    @Test
    fun `dismissing another user's subscription is a 404 and keeps the flag`() = routeTest {
        val sub = subscriptions.save(subscription(userId = otherUserId))

        val (status, _) = call(HttpMethod.Patch, "/api/v1/subscriptions/${sub.id}/dismiss", "{}")

        assertEquals(HttpStatusCode.NotFound, status)
        assertTrue(subscriptions.findById(sub.id)!!.isFlagged)
    }

    // ── POST /subscriptions/{id}/cancel ──

    @Test
    fun `cancelling a subscription queues a draft and audits the cancel`() = routeTest {
        val sub = subscriptions.save(subscription())

        val (status, body) = call(HttpMethod.Post, "/api/v1/subscriptions/${sub.id}/cancel")

        assertEquals(HttpStatusCode.Accepted, status)
        val data = body["data"]!!.jsonObject
        assertEquals(sub.id.toString(), data.str("subscriptionId"))
        assertEquals("queued", data.str("status"))
        assertEquals(AuditActions.SUBSCRIPTION_CANCEL, auditRepo.findByEntity("subscription", sub.id).single().action)
    }

    @Test
    fun `cancelling a subscription with a non-uuid id is a 400`() = routeTest {
        val (status, _) = call(HttpMethod.Post, "/api/v1/subscriptions/123/cancel")
        assertEquals(HttpStatusCode.BadRequest, status)
    }

    @Test
    fun `cancelling an unknown subscription is a 404`() = routeTest {
        val (status, _) = call(HttpMethod.Post, "/api/v1/subscriptions/${UUID.randomUUID()}/cancel")
        assertEquals(HttpStatusCode.NotFound, status)
    }

    @Test
    fun `cancelling another user's subscription is a 404 and writes no audit`() = routeTest {
        val sub = subscriptions.save(subscription(userId = otherUserId))

        val (status, _) = call(HttpMethod.Post, "/api/v1/subscriptions/${sub.id}/cancel")

        assertEquals(HttpStatusCode.NotFound, status)
        assertTrue(auditRepo.findByEntity("subscription", sub.id).isEmpty())
    }

    // ── agentModule (Koin wiring) ──

    @Test
    fun `agentModule mounts the agent routes from Koin dependencies`() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(Koin) {
                modules(module {
                    single<AnomalyRepository> { anomalies }
                    single<BriefingRepository> { briefings }
                    single<InsightRepository> { insights }
                    single<SubscriptionRepository> { subscriptions }
                    single { auditService }
                })
            }
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            agentModule()
        }
        val a = anomalies.save(anomaly())

        val (status, body) = call(HttpMethod.Get, "/api/v1/anomalies/${a.id}")

        assertEquals(HttpStatusCode.OK, status)
        assertEquals(a.id.toString(), body["data"]!!.str("id"))
    }
}
