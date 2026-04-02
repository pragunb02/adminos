package dev.adminos.api.domain.ingestion

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.domain.ingestion.connection.ConnectionService
import dev.adminos.api.domain.ingestion.connection.InMemoryConnectionRepository
import dev.adminos.api.domain.ingestion.sync.InMemorySyncSessionRepository
import dev.adminos.api.domain.ingestion.sync.IngestionService
import dev.adminos.api.domain.ingestion.sync.ingestionRoutes
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.config.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.*

/**
 * Integration tests for ingestion HTTP endpoints.
 * Uses Ktor testApplication with a real JWT to test the full HTTP flow.
 * Validates Requirements 3.1, 3.2, 3.3, 3.6, 3.7
 */
class IngestionRoutesTest {

    private val config = AppConfig.load()
    private val jwtService = JwtService(config.auth)
    private val testUser = User(
        id = UUID.randomUUID(),
        email = "test@example.com",
        googleId = "google_test_123",
        role = UserRole.OWNER
    )
    private val validJwt = jwtService.generateAccessToken(testUser)

    private fun ApplicationTestBuilder.configureTestApp(): IngestionService {
        val syncRepo = InMemorySyncSessionRepository()
        val connRepo = InMemoryConnectionRepository()
        val ingestionService = IngestionService(syncRepo)
        val connectionService = ConnectionService(connRepo, syncRepo)

        environment {
            config = MapApplicationConfig()
        }

        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            configureRequestId()
            configureErrorHandling()

            install(Authentication) {
                bearer("auth-jwt") {
                    authenticate { tokenCredential ->
                        val claims = jwtService.verifyAccessToken(tokenCredential.token)
                        if (claims != null) {
                            UserPrincipal(
                                userId = claims.userId,
                                email = claims.email,
                                role = claims.role
                            )
                        } else null
                    }
                }
            }

            routing {
                route("/api/v1") {
                    ingestionRoutes(ingestionService, connectionService)
                }
            }
        }
        return ingestionService
    }

    @Test
    fun `POST ingest sms without JWT returns 401`() = testApplication {
        configureTestApp()

        val response = client.post("/api/v1/ingest/sms") {
            contentType(ContentType.Application.Json)
            setBody("""{"deviceId":"dev1","batch":[{"merchant":"ZOMATO","amount":450,"date":"2025-01-15","accountLast4":"4521"}]}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST ingest sms with valid JWT returns 202 Accepted`() = testApplication {
        configureTestApp()

        val response = client.post("/api/v1/ingest/sms") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $validJwt")
            setBody("""{"deviceId":"dev1","batch":[{"merchant":"ZOMATO","amount":450,"date":"2025-01-15","accountLast4":"4521"}]}""")
        }

        assertEquals(HttpStatusCode.Accepted, response.status)

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("true", body["success"]?.jsonPrimitive?.content)

        val data = body["data"]?.jsonObject
        assertNotNull(data)
        assertEquals("queued", data["status"]?.jsonPrimitive?.content)
        assertEquals("1", data["totalItems"]?.jsonPrimitive?.content)
        assertNotNull(data["syncSessionId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST ingest sms with raw text returns 400 INGEST_003`() = testApplication {
        configureTestApp()

        val response = client.post("/api/v1/ingest/sms") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $validJwt")
            setBody("""{"deviceId":"dev1","batch":[{"merchant":"ZOMATO","amount":450,"date":"2025-01-15","accountLast4":"4521","rawText":"Your account debited"}]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val error = body["error"]?.jsonObject
        assertEquals("INGEST_003", error?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `GET sync session returns session data`() = testApplication {
        val service = configureTestApp()

        // Create a session first via the service directly
        val records = listOf(
            SmsRecord(merchant = "ZOMATO", amount = 450.0, date = "2025-01-15", accountLast4 = "4521")
        )
        val session = service.ingestSmsBatch(testUser.id, UUID.randomUUID(), records)

        val response = client.get("/api/v1/sync/${session.id}") {
            header(HttpHeaders.Authorization, "Bearer $validJwt")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = body["data"]?.jsonObject
        assertNotNull(data)
        assertEquals("queued", data["status"]?.jsonPrimitive?.content)
        assertEquals("1", data["totalItems"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET sync session for unknown ID returns 404`() = testApplication {
        configureTestApp()

        val response = client.get("/api/v1/sync/${UUID.randomUUID()}") {
            header(HttpHeaders.Authorization, "Bearer $validJwt")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
