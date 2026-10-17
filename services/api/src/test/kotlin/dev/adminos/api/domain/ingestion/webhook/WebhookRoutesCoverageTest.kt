package dev.adminos.api.domain.ingestion.webhook

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.PlainHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import dev.adminos.api.domain.identity.assertWireFormat
import dev.adminos.api.domain.ingestion.connection.ConnectionService
import dev.adminos.api.domain.ingestion.connection.InMemoryConnectionRepository
import dev.adminos.api.domain.ingestion.connection.SourceType
import dev.adminos.api.domain.ingestion.events.InMemoryIngestEventRepository
import dev.adminos.api.domain.ingestion.events.IngestEventService
import dev.adminos.api.domain.ingestion.sync.InMemorySyncSessionRepository
import dev.adminos.api.domain.ingestion.sync.IngestionService
import dev.adminos.api.domain.ingestion.sync.SyncSessionStatus
import dev.adminos.api.domain.ingestion.sync.SyncType
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import dev.adminos.api.infrastructure.auth.OidcVerificationException
import dev.adminos.api.infrastructure.auth.OidcVerifier
import dev.adminos.api.infrastructure.auth.PushTokenVerifier
import java.util.Base64
import java.util.Date
import java.util.UUID
import kotlin.test.*

/** AOS-312: Gmail Pub/Sub push webhook: authorization, message decoding and routing to a connection. */
class WebhookRoutesCoverageTest {

    private val syncSessions = InMemorySyncSessionRepository()
    private val connections = ConnectionService(InMemoryConnectionRepository(), syncSessions)
    private val ingestion = IngestionService(IngestEventService(InMemoryIngestEventRepository()), syncSessions)
    private val userId = UUID.randomUUID()
    private val audience = "https://api.adminos.example.in/api/v1/webhooks/gmail"

    private val pushAccount = "gmail-api-push@system.gserviceaccount.com"
    /** Accepts the test token only: stands in for a verified Pub/Sub push (OidcVerifier has its own tests). */
    private val acceptsTestToken = PushTokenVerifier { token ->
        if (token != "pubsub-oidc-token") throw OidcVerificationException("not the test token")
        com.nimbusds.jwt.JWTClaimsSet.Builder().build()
    }

    private fun webhookTest(verifier: PushTokenVerifier? = acceptsTestToken, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling()
            routing { route("/api/v1") { webhookRoutes(connections, ingestion, verifier) } }
        }
        block()
    }

    private fun webhookTest(audience: String, block: suspend ApplicationTestBuilder.() -> Unit) =
        webhookTest(OidcVerifier(audience, pushAccount), block)

    private fun push(notification: String) =
        """{"message":{"data":"${Base64.getEncoder().encodeToString(notification.toByteArray())}","messageId":"136969346945"},"subscription":"projects/adminos/subscriptions/gmail-push"}"""

    private suspend fun ApplicationTestBuilder.deliver(body: String, authorization: String? = "Bearer pubsub-oidc-token") =
        client.post("/api/v1/webhooks/gmail") {
            if (authorization != null) header(HttpHeaders.Authorization, authorization)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.errorCode() = json()["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
    private suspend fun HttpResponse.data() = json()["data"]!!.jsonObject

    private fun gmail(address: String = "owner@example.com") = runBlocking { connections.createConnection(userId, SourceType.GMAIL, address) }

    // --- Authorization ---

    @Test
    fun `a push without an authorization header is a 401`() = webhookTest {
        val res = deliver(push("""{"emailAddress":"owner@example.com"}"""), authorization = null)

        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("WEBHOOK_005", res.errorCode())
    }

    @Test
    fun `a push with a non-bearer authorization is a 401`() = webhookTest {
        val res = deliver(push("""{"emailAddress":"owner@example.com"}"""), authorization = "Basic cHVic3ViOnNlY3JldA==")

        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("WEBHOOK_005", res.errorCode())
    }

    @Test
    fun `with an audience configured a malformed token is rejected`() = webhookTest(audience) {
        gmail()

        val res = deliver(push("""{"emailAddress":"owner@example.com","historyId":9876}"""), authorization = "Bearer not-a-jwt")

        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("WEBHOOK_006", res.errorCode())
        assertTrue(syncSessions.findByUserId(userId).isEmpty())
    }

    @Test
    fun `with an audience configured an unsigned alg-none token is rejected`() = webhookTest(audience) {
        val unsigned = PlainJWT(PlainHeader(), claims()).serialize()

        val res = deliver(push("""{"emailAddress":"owner@example.com"}"""), authorization = "Bearer $unsigned")

        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("WEBHOOK_006", res.errorCode())
    }

    @Test
    fun `with an audience configured an hs256 token signed with a guessable key is rejected`() = webhookTest(audience) {
        val hs = SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims()).apply { sign(MACSigner("a-shared-secret-that-is-32-bytes-long!!")) }.serialize()

        val res = deliver(push("""{"emailAddress":"owner@example.com"}"""), authorization = "Bearer $hs")

        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("WEBHOOK_006", res.errorCode())
    }

    private fun claims() = JWTClaimsSet.Builder()
        .issuer("https://accounts.google.com").audience(audience).subject("1234")
        .issueTime(Date()).expirationTime(Date(System.currentTimeMillis() + 600_000)).build()

    @Test
    fun `without configuration every push is refused and nothing syncs (AOS-609 regression, AOS-601)`() = webhookTest(verifier = null) {
        gmail()

        val res = deliver(push("""{"emailAddress":"owner@example.com","historyId":9876}"""), authorization = "Bearer anything")

        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
        assertEquals("WEBHOOK_007", res.errorCode())
        assertTrue(syncSessions.findByUserId(userId).isEmpty())
    }

    @Test
    fun `the production wiring refuses everything until both audience and service account are set`() {
        for ((aud, sa) in listOf("" to "", audience to "", "" to pushAccount)) testApplication {
            environment { config = MapApplicationConfig() }
            application {
                configureSerialization(); configureRequestId(); configureErrorHandling()
                routing { route("/api/v1") { webhookRoutes(connections, ingestion, aud, sa) } }
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, deliver(push("""{"emailAddress":"x@example.com"}""")).status)
        }
    }

    @Test
    fun `a token the verifier rejects is a 401 and nothing syncs`() = webhookTest {
        gmail()
        val res = deliver(push("""{"emailAddress":"owner@example.com","historyId":9876}"""), authorization = "Bearer forged")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertEquals("WEBHOOK_006", res.errorCode())
        assertTrue(syncSessions.findByUserId(userId).isEmpty())
    }

    // --- Decoding ---

    @Test
    fun `a body that is not a pub-sub push is a 400`() = webhookTest {
        val res = deliver("""{"hello":"world"}""")

        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("WEBHOOK_001", res.errorCode())
    }

    @Test
    fun `message data that is not base64 is a 400`() = webhookTest {
        val res = deliver("""{"message":{"data":"%%% not base64 %%%"}}""")

        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("WEBHOOK_002", res.errorCode())
    }

    @Test
    fun `message data that is not a gmail notification is a 400`() = webhookTest {
        val res = deliver(push("historyId=9876"))

        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("WEBHOOK_003", res.errorCode())
    }

    @Test
    fun `a notification with a blank email address is a 400`() = webhookTest {
        val res = deliver(push("""{"emailAddress":"  ","historyId":9876}"""))

        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("WEBHOOK_004", res.errorCode())
    }

    // --- Routing ---

    @Test
    fun `a notification for an unknown mailbox is acknowledged without a sync`() = webhookTest {
        val res = deliver(push("""{"emailAddress":"stranger@example.com","historyId":9876}"""))

        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("No matching connection", res.data()["message"]!!.jsonPrimitive.content)
        assertTrue(syncSessions.findByUserId(userId).isEmpty())
    }

    @Test
    fun `a notification for a disconnected mailbox is acknowledged without a sync`() = webhookTest {
        runBlocking { connections.disconnect(userId, gmail().id) }

        val res = deliver(push("""{"emailAddress":"owner@example.com","historyId":9876}"""))

        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("Connection not active", res.data()["message"]!!.jsonPrimitive.content)
        assertTrue(syncSessions.findByUserId(userId).isEmpty())
    }

    @Test
    fun `a notification with a history id queues an incremental sync for that connection`() = webhookTest {
        val conn = gmail()

        val res = deliver(push("""{"emailAddress":"owner@example.com","historyId":9876}"""))

        assertEquals(HttpStatusCode.OK, res.status)
        val session = syncSessions.findByUserId(userId).single()
        assertEquals(conn.id, session.connectionId)
        assertEquals(SyncType.INCREMENTAL, session.syncType)
        assertEquals(session.id.toString(), res.data()["syncSessionId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a notification without a history id still queues an incremental sync (AOS-405)`() = webhookTest {
        gmail()

        deliver(push("""{"emailAddress":"owner@example.com"}"""))

        // The worker starts from the history ID stored on the connection either way (D-59).
        assertEquals(SyncType.INCREMENTAL, syncSessions.findByUserId(userId).single().syncType)
    }

    @Test
    fun `a completed push sync makes the daily fallback skip the connection (AOS-405)`() = webhookTest {
        val conn = gmail()
        deliver(push("""{"emailAddress":"owner@example.com","historyId":9876}"""))
        val pushed = syncSessions.findByUserId(userId).single()
        // The worker finishes the push sync.
        syncSessions.update(pushed.copy(status = SyncSessionStatus.COMPLETED, completedAt = java.time.Instant.now()))

        val result = GmailCronService(connections, ingestion, syncSessions).runDailyFallbackSync()

        assertEquals(DailySyncResult(totalConnections = 1, enqueued = 0, skipped = 1, errors = 0), result)
        assertEquals(listOf(pushed.id), syncSessions.findByUserId(userId).filter { it.connectionId == conn.id }.map { it.id })
    }

    // --- Wire formats ---

    @Test
    fun `pub-sub push message round-trips with and without the subscription`() =
        assertWireFormat(PubSubPushMessage.serializer(), PubSubPushMessage(PubSubMessage("e30=")),
            PubSubPushMessage(PubSubMessage("e30=", "1", "2026-10-16T06:00:00Z"), "projects/p/subscriptions/s"), """{"subscription":"s"}""")

    @Test
    fun `pub-sub message round-trips with and without its metadata`() =
        assertWireFormat(PubSubMessage.serializer(), PubSubMessage("e30="), PubSubMessage("e30=", "136969346945", "2026-10-16T06:00:00Z"),
            """{"messageId":"1"}""")

    @Test
    fun `gmail notification round-trips with and without a history id`() =
        assertWireFormat(GmailNotification.serializer(), GmailNotification("owner@example.com"), GmailNotification("owner@example.com", 9876),
            """{"historyId":1}""")

    @Test
    fun `webhook ack round-trips with a message or a sync session id`() =
        assertWireFormat(WebhookAckResponse.serializer(), WebhookAckResponse(true), WebhookAckResponse(true, "queued", "s-1"), """{"message":"x"}""")
}
