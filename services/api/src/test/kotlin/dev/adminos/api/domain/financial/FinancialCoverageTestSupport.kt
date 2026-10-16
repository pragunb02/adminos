package dev.adminos.api.domain.financial

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserRole
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.ZoneId
import java.util.UUID

/**
 * Shared harness for the AOS-312 domain/financial route tests: production serialization, request IDs,
 * error mapping and cookie JWT auth, with the routes under test mounted at /api/v1.
 */
internal object FinancialCoverageSupport {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    val jwtService = JwtService(AppConfig.load().auth)

    fun newUser(email: String = "pragun@example.in", timezone: String = "Asia/Kolkata"): User =
        User(id = UUID.randomUUID(), email = email, googleId = "g-${UUID.randomUUID()}", role = UserRole.OWNER, timezone = timezone)

    fun tokenFor(user: User): String = jwtService.generateAccessToken(user)

    fun routeTest(routes: Route.() -> Unit, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization()
            configureRequestId()
            configureErrorHandling()
            configureAuthentication(jwtService)
            routing { route("/api/v1") { routes() } }
        }
        block()
    }

    suspend fun ApplicationTestBuilder.getAs(token: String?, path: String): Pair<HttpStatusCode, JsonObject> {
        val res = client.get(path) { if (token != null) cookie(ACCESS_TOKEN_COOKIE, token) }
        return res.status to Json.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    suspend fun ApplicationTestBuilder.patchAs(token: String, path: String, json: String): Pair<HttpStatusCode, JsonObject> {
        val res = client.patch(path) {
            cookie(ACCESS_TOKEN_COOKIE, token); contentType(ContentType.Application.Json); setBody(json)
        }
        return res.status to Json.parseToJsonElement(res.bodyAsText()).jsonObject
    }

    fun JsonObject.data(): JsonObject = this["data"]!!.jsonObject
    fun JsonObject.dataArray(): JsonArray = this["data"]!!.jsonArray
    fun JsonObject.pagination(): JsonObject = this["pagination"]!!.jsonObject
    fun JsonObject.errorCode(): String = this["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
    fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
    fun JsonObject.isNull(key: String): Boolean = this[key] is JsonNull
    fun JsonObject.money(key: String): BigDecimal = BigDecimal(this[key]!!.jsonPrimitive.content)
}
