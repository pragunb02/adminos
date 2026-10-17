package dev.adminos.api.domain.ingestion.connection

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.audit.AuditActions
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.InMemoryAuditRepository
import dev.adminos.api.domain.identity.FakeGoogleServer
import dev.adminos.api.domain.identity.GoogleOAuthClient
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.ingestion.sync.InMemorySyncSessionRepository
import dev.adminos.api.domain.ingestion.sync.SyncType
import dev.adminos.api.infrastructure.crypto.TokenEncryptor
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
import org.junit.jupiter.api.Disabled
import java.util.UUID
import kotlin.test.*

/** AOS-312: /connections routes: create (incl. Gmail OAuth via a local fake Google), list, disconnect, sync. */
class ConnectionRoutesCoverageTest {

    private val config = AppConfig.load()
    private val jwt = JwtService(config.auth)
    private val google = FakeGoogleServer()
    private val connections = InMemoryConnectionRepository()
    private val syncSessions = InMemorySyncSessionRepository()
    private val encryptor = TokenEncryptor(java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() }))
    private val service = ConnectionService(connections, syncSessions, encryptor)
    private val audit = InMemoryAuditRepository()
    private val me = User(id = UUID.randomUUID(), email = "pragun.b@gmail.com", googleId = "g-me")
    private val token = jwt.generateAccessToken(me)
    private val redirect = "http://localhost:3000/settings/connections"

    @AfterTest
    fun stop() = google.close()

    private fun routesTest(oauth: GoogleOAuthClient? = google.client(config.auth), block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { this.config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwt)
            routing { route("/api/v1") { connectionRoutes(service, AuditService(audit, CoroutineScope(Dispatchers.Unconfined)), oauth) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.create(body: String) = client.post("/api/v1/connections") {
        bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
    }

    private suspend fun HttpResponse.json() = Json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.errorCode() = json()["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content

    private fun connect(userId: UUID = me.id, source: SourceType = SourceType.SMS) = runBlocking { service.createConnection(userId, source) }

    @Test
    fun `connections need a login`() = routesTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/connections").status)
    }

    @Test
    fun `the routes can be mounted without a google client for non-oauth sources`() = testApplication {
        environment { this.config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwt)
            routing { route("/api/v1") { connectionRoutes(service, AuditService(audit, CoroutineScope(Dispatchers.Unconfined))) } }
        }

        assertEquals(HttpStatusCode.Created, create("""{"sourceType":"pdf"}""").status)
    }

    @Test
    fun `listing returns only my connections`() = routesTest {
        val mine = connect()
        connect(UUID.randomUUID())

        val res = client.get("/api/v1/connections") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(listOf(mine.id.toString()), res.json()["data"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
    }

    @Test
    fun `creating an sms connection is a 201 and is audited`() = routesTest {
        val res = create("""{"sourceType":"sms"}""")

        assertEquals(HttpStatusCode.Created, res.status)
        val id = UUID.fromString(res.json()["data"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("connected", res.json()["data"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(AuditActions.CONNECTION_CREATE, audit.findByEntity("connection", id).single().action)
    }

    @Test
    fun `an unknown source type is a 400`() = routesTest {
        val res = create("""{"sourceType":"carrier-pigeon"}""")

        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("CONN_003", res.errorCode())
    }

    @Test
    fun `connecting gmail with a code stores google's address and encrypted tokens`() = routesTest {
        val res = create("""{"sourceType":"gmail","code":"4/0AbCdEf","redirectUri":"$redirect"}""")

        assertEquals(HttpStatusCode.Created, res.status)
        assertEquals("pragun.b@gmail.com", res.json()["data"]!!.jsonObject["gmailAddress"]!!.jsonPrimitive.content)
        val stored = assertNotNull(service.findByGmailAddress("pragun.b@gmail.com"))
        assertEquals("ya29.a0-test-access", encryptor.decrypt(stored.accessToken!!, "user_connections:${stored.id}:access_token"))
        assertEquals("1//0g-test-refresh", encryptor.decrypt(stored.refreshToken!!, "user_connections:${stored.id}:refresh_token"))
        assertEquals(redirect, google.lastTokenForm()["redirect_uri"])
    }

    @Test
    fun `connecting gmail stores when the access token expires and what was granted`() = routesTest {
        create("""{"sourceType":"gmail","code":"4/0AbCdEf","redirectUri":"$redirect"}""")

        val stored = assertNotNull(service.findByGmailAddress("pragun.b@gmail.com"))
        val expires = assertNotNull(stored.tokenExpiresAt)
        assertTrue(expires.isAfter(java.time.Instant.now().plusSeconds(3000)) && expires.isBefore(java.time.Instant.now().plusSeconds(3600)))
        assertTrue(GMAIL_READONLY_SCOPE in stored.oauthScope.orEmpty())
    }

    @Test
    fun `a google grant without gmail read access is CONN_007 and nothing is stored`() = routesTest {
        google.tokenBody = """{"access_token":"ya29.a0-test-access","expires_in":3599,"refresh_token":"1//0g-test-refresh","scope":"openid email"}"""

        val res = create("""{"sourceType":"gmail","code":"4/0AbCdEf","redirectUri":"$redirect"}""")

        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("CONN_007", res.errorCode())
        assertNull(service.findByGmailAddress("pragun.b@gmail.com"))
    }

    @Test
    fun `a google grant without offline access is CONN_008`() = routesTest {
        google.tokenBody = """{"access_token":"ya29.a0-test-access","expires_in":3599,"scope":"openid email $GMAIL_READONLY_SCOPE"}"""

        val res = create("""{"sourceType":"gmail","code":"4/0AbCdEf","redirectUri":"$redirect"}""")

        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("CONN_008", res.errorCode())
    }

    @Test
    fun `the gmail authorize url asks google for read-only gmail with offline access`() =
        routesTest(oauth = google.client(dev.adminos.api.domain.identity.identityTestAuthConfig())) {
            val res = client.get("/api/v1/connections/gmail/authorize-url") {
                bearerAuth(token); parameter("redirectUri", redirect)
            }

            assertEquals(HttpStatusCode.OK, res.status)
            val url = java.net.URI(res.json()["data"]!!.jsonObject["url"]!!.jsonPrimitive.content)
            assertEquals("accounts.google.com", url.host)
            val query = url.rawQuery.split('&').associate {
                val (k, v) = it.split('=', limit = 2)
                k to java.net.URLDecoder.decode(v, Charsets.UTF_8)
            }
            assertEquals("openid email $GMAIL_READONLY_SCOPE", query["scope"])
            assertEquals(redirect, query["redirect_uri"])
            assertEquals("offline", query["access_type"])
            assertEquals("consent", query["prompt"])
            assertEquals("code", query["response_type"])
            assertEquals("test-client-id.apps.googleusercontent.com", query["client_id"])
        }

    @Test
    fun `the gmail authorize url needs a redirect uri and a configured google client`() = routesTest(oauth = null) {
        val noRedirect = client.get("/api/v1/connections/gmail/authorize-url") { bearerAuth(token) }
        val noClient = client.get("/api/v1/connections/gmail/authorize-url") { bearerAuth(token); parameter("redirectUri", redirect) }

        assertEquals(HttpStatusCode.BadRequest, noRedirect.status)
        assertEquals("CONN_005", noRedirect.errorCode())
        assertEquals(HttpStatusCode.ServiceUnavailable, noClient.status)
        assertEquals("CONN_009", noClient.errorCode())
    }

    @Test
    fun `the gmail authorize url is unavailable when the google client id is blank`() =
        routesTest(oauth = google.client(dev.adminos.api.domain.identity.identityTestAuthConfig().copy(googleClientId = ""))) {
            val res = client.get("/api/v1/connections/gmail/authorize-url") { bearerAuth(token); parameter("redirectUri", redirect) }

            assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
        }

    @Test
    fun `the gmail connect response never contains the oauth tokens`() = routesTest {
        val body = create("""{"sourceType":"gmail","code":"4/0AbCdEf","redirectUri":"$redirect"}""").bodyAsText()

        assertFalse("ya29" in body || "1//0g" in body)
    }

    @Test
    fun `a gmail code without a redirect uri is a 400 and google is not called`() = routesTest {
        val res = create("""{"sourceType":"gmail","code":"4/0AbCdEf"}""")

        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("CONN_005", res.errorCode())
        assertTrue(google.tokenRequests.isEmpty())
    }

    @Test
    fun `a gmail code google rejects is OAUTH_FAILED, not a server error`() = routesTest {
        google.tokenStatus = 400
        google.tokenBody = """{"error":"invalid_grant"}"""

        val res = create("""{"sourceType":"gmail","code":"4/expired","redirectUri":"$redirect"}""")

        assertNotEquals(HttpStatusCode.InternalServerError, res.status)
        assertEquals("CONN_002", res.errorCode())
    }

    @Test
    fun `connecting gmail without an oauth code is rejected`() = routesTest(oauth = null) {
        val res = create("""{"sourceType":"gmail","code":"4/0AbCdEf","redirectUri":"$redirect"}""")

        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertNull(service.findByGmailAddress("user@gmail.com"))
    }

    @Test
    fun `disconnecting my connection clears it and is audited`() = routesTest {
        val mine = connect()

        val res = client.delete("/api/v1/connections/${mine.id}") { bearerAuth(token) }

        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("disconnected", res.json()["data"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(AuditActions.CONNECTION_DISCONNECT, audit.findByEntity("connection", mine.id).single().action)
    }

    @Test
    fun `disconnecting another user's connection is a 404 and leaves it connected`() = routesTest {
        val theirs = connect(UUID.randomUUID())

        val res = client.delete("/api/v1/connections/${theirs.id}") { bearerAuth(token) }

        assertEquals(HttpStatusCode.NotFound, res.status)
        assertEquals("CONN_001", res.errorCode())
        assertEquals(ConnectionStatus.CONNECTED, connections.findById(theirs.id)?.status)
    }

    @Test
    fun `disconnecting with a malformed id is a 400`() = routesTest {
        assertEquals(HttpStatusCode.BadRequest, client.delete("/api/v1/connections/not-a-uuid") { bearerAuth(token) }.status)
    }

    @Test
    fun `a manual sync of my connection is queued`() = routesTest {
        val mine = connect()

        val res = client.post("/api/v1/connections/${mine.id}/sync") { bearerAuth(token) }

        assertEquals(HttpStatusCode.Accepted, res.status)
        assertEquals("queued", res.json()["data"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(SyncType.MANUAL, syncSessions.findByUserId(me.id).single().syncType)
    }

    @Test
    fun `a manual sync of a disconnected connection is a 400`() = routesTest {
        val mine = connect()
        runBlocking { service.disconnect(me.id, mine.id) }

        val res = client.post("/api/v1/connections/${mine.id}/sync") { bearerAuth(token) }

        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertEquals("CONN_004", res.errorCode())
    }

    @Test
    fun `a manual sync of another user's connection is a 404`() = routesTest {
        val theirs = connect(UUID.randomUUID())

        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/connections/${theirs.id}/sync") { bearerAuth(token) }.status)
    }

    @Test
    fun `the sixth manual sync in an hour is rate limited`() = routesTest {
        val user = User(id = UUID.randomUUID(), email = "sync@example.com", googleId = "g-sync")
        val userToken = jwt.generateAccessToken(user)
        val conn = connect(user.id)
        repeat(5) { assertEquals(HttpStatusCode.Accepted, client.post("/api/v1/connections/${conn.id}/sync") { bearerAuth(userToken) }.status) }

        val res = client.post("/api/v1/connections/${conn.id}/sync") { bearerAuth(userToken) }

        assertEquals(HttpStatusCode.TooManyRequests, res.status)
        assertEquals("RATE_001", res.errorCode())
    }
}
