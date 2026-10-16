package dev.adminos.api.infrastructure.plugins

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserRole
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.config.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Production auth (configureAuthentication): Bearer header or the access_token cookie (AOS-106). */
class AuthenticationTest {

    private val jwtService = JwtService(AppConfig.load().auth)
    private val user = User(id = UUID.randomUUID(), email = "me@example.com", googleId = "g-1", role = UserRole.OWNER)
    private val validJwt = jwtService.generateAccessToken(user)

    private fun authTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization()
            configureRequestId()
            configureErrorHandling()
            configureAuthentication(jwtService)
            routing {
                authenticate("auth-jwt") { get("/me") { call.respondText(call.userPrincipal.userId.toString()) } }
                get("/expired") { throw ApiException(HttpStatusCode.Unauthorized, dev.adminos.api.domain.common.ApiError.TOKEN_EXPIRED) }
            }
        }
        block()
    }

    @Test
    fun `bearer header authenticates`() = authTest {
        val res = client.get("/me") { header(HttpHeaders.Authorization, "Bearer $validJwt") }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(user.id.toString(), res.bodyAsText())
    }

    @Test
    fun `access_token cookie authenticates when there is no header`() = authTest {
        val res = client.get("/me") { cookie(ACCESS_TOKEN_COOKIE, validJwt) }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals(user.id.toString(), res.bodyAsText())
    }

    @Test
    fun `invalid cookie is rejected`() = authTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/me") { cookie(ACCESS_TOKEN_COOKIE, "not-a-jwt") }.status)
    }

    @Test
    fun `blank cookie is rejected`() = authTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/me") { cookie(ACCESS_TOKEN_COOKIE, "") }.status)
    }

    @Test
    fun `no credentials is rejected`() = authTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/me").status)
    }

    @Test
    fun `401 carries the standard JSON error envelope`() = authTest {
        val res = client.get("/me")
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        val error = kotlinx.serialization.json.Json.parseToJsonElement(res.bodyAsText())
            .let { it as kotlinx.serialization.json.JsonObject }["error"].toString()
        assertTrue("AUTH_002" in error, "body was: ${res.bodyAsText()}")
    }

    @Test
    fun `specific 401 errors keep their own code`() = authTest {
        val body = client.get("/expired").bodyAsText()
        assertTrue("AUTH_001" in body, "body was: $body")
    }

    @Test
    fun `header wins over cookie`() = authTest {
        val res = client.get("/me") {
            header(HttpHeaders.Authorization, "Bearer not-a-jwt")
            cookie(ACCESS_TOKEN_COOKIE, validJwt)
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status, "an explicit (bad) header must not fall back to the cookie")
    }
}
