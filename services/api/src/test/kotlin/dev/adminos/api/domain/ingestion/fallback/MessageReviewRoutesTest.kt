package dev.adminos.api.domain.ingestion.fallback

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestDirection
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-705: Review of AI-read messages is owner-only; another user's proposal is 404; bodies never carry text. */
class MessageReviewRoutesTest {
    private val jwt = JwtService(AppConfig.load().auth)
    private val me = User(id = UUID.randomUUID(), email = "me@example.com", googleId = "g-1", role = UserRole.OWNER)
    private val them = User(id = UUID.randomUUID(), email = "them@example.com", googleId = "g-2", role = UserRole.OWNER)
    private val proposals = InMemoryMessageProposalRepository()
    private val events = InMemoryIngestEventRepository()
    private val service = MessageReviewService(proposals, IngestEventService(events))

    private fun proposal(user: UUID, ref: String) = MessageProposal(
        userId = user, channel = MessageChannel.SMS, sourceRef = ref, occurredAt = Instant.parse("2026-10-16T05:00:00Z"),
        direction = IngestDirection.DEBIT, amount = BigDecimal("249.00"), accountLast4 = "4321", merchant = "<b>ZEPTO</b>",
        bankCode = null, model = "qwen3:4b", promptVersion = "read-message-v1", createdAt = Instant.parse("2026-10-16T05:01:00Z")
    ).also { runBlocking { proposals.saveNew(it) } }

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwt)
            routing { route("/api/v1") { messageReviewRoutes(service) } }
        }
        block()
    }

    private fun HttpRequestBuilder.signedIn(user: User = me) = cookie(ACCESS_TOKEN_COOKIE, jwt.generateAccessToken(user))

    @Test
    fun `every route needs a signed-in user`() = routeTest {
        val p = proposal(me.id, "r1")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/review/messages").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/review/messages/${p.id}/confirm").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/review/messages/${p.id}/dismiss").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/review/messages") { cookie(ACCESS_TOKEN_COOKIE, "forged.token.value") }.status)
        assertEquals(ProposalStatus.PENDING, proposals.find(me.id, p.id)?.status)
    }

    @Test
    fun `the list holds only my pending proposals with their fields`() = routeTest {
        val mine = proposal(me.id, "r1")
        proposal(them.id, "r2")
        val res = client.get("/api/v1/review/messages") { signedIn() }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue(mine.id.toString() in body, body)
        assertTrue("\"amount\":\"249.00\"" in body && "\"direction\":\"debit\"" in body && "\"readBy\":\"qwen3:4b\"" in body && "\"channel\":\"sms\"" in body, body)
        assertTrue("\"accountLast4\":\"4321\"" in body && "\"occurredAt\":\"2026-10-16T05:00:00Z\"" in body, body)
        assertEquals(1, Regex("\"id\":").findAll(body).count(), body)
    }

    @Test
    fun `confirm turns my proposal into an intake event`() = routeTest {
        val p = proposal(me.id, "r1")
        val res = client.post("/api/v1/review/messages/${p.id}/confirm") { signedIn() }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        assertTrue("\"status\":\"confirmed\"" in res.bodyAsText() && "\"duplicate\":false" in res.bodyAsText())
        assertEquals(1, events.findByUser(me.id).size)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/review/messages/${p.id}/confirm") { signedIn() }.status, "already decided")
    }

    @Test
    fun `dismiss keeps my proposal out of the totals`() = routeTest {
        val p = proposal(me.id, "r1")
        val res = client.post("/api/v1/review/messages/${p.id}/dismiss") { signedIn() }
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue("\"status\":\"dismissed\"" in res.bodyAsText())
        assertTrue(events.findByUser(me.id).isEmpty())
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/review/messages/${p.id}/dismiss") { signedIn() }.status)
    }

    @Test
    fun `another user's proposal is not found and stays untouched`() = routeTest {
        val theirs = proposal(them.id, "r9")
        val c = client.post("/api/v1/review/messages/${theirs.id}/confirm") { signedIn() }
        assertEquals(HttpStatusCode.NotFound, c.status)
        assertTrue("REVIEW_001" in c.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/review/messages/${theirs.id}/dismiss") { signedIn() }.status)
        assertEquals(ProposalStatus.PENDING, proposals.find(them.id, theirs.id)?.status)
        assertTrue(events.findByUser(them.id).isEmpty())
    }

    @Test
    fun `a malformed id is a 400 that doesn't echo it`() = routeTest {
        val res = client.post("/api/v1/review/messages/not-a-uuid%3Cscript%3E/confirm") { signedIn() }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertFalse("script" in res.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/review/messages/x/dismiss") { signedIn() }.status)
    }

    @Test
    fun `untrusted merchant text is returned as escaped JSON data`() = routeTest {
        proposal(me.id, "r1")
        val body = client.get("/api/v1/review/messages") { signedIn() }.bodyAsText()
        assertTrue("\"merchant\":\"<b>ZEPTO</b>\"" in body, body) // JSON data; the web renders it as text
        assertEquals(ContentType.Application.Json.withoutParameters(), client.get("/api/v1/review/messages") { signedIn() }.contentType()?.withoutParameters())
    }
}
