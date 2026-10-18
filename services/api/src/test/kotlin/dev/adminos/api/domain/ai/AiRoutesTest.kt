package dev.adminos.api.domain.ai

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.ai.eval.AiEvalAnswers
import dev.adminos.api.domain.ai.eval.AiEvalRecord
import dev.adminos.api.domain.ai.eval.AiEvalRecords
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.audit.InMemoryAuditRepository
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.infrastructure.plugins.ACCESS_TOKEN_COOKIE
import dev.adminos.api.infrastructure.plugins.CROSS_SITE_REFUSED
import dev.adminos.api.infrastructure.plugins.OriginCheck
import io.ktor.server.application.install
import dev.adminos.api.infrastructure.plugins.configureAuthentication
import dev.adminos.api.infrastructure.plugins.configureErrorHandling
import dev.adminos.api.infrastructure.plugins.configureRequestId
import dev.adminos.api.infrastructure.plugins.configureSerialization
import io.ktor.client.request.cookie
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `/api/v1/ai/features` (AOS-704): auth, per-user scope, consent changes, and the D-100 attack cases. */
class AiRoutesTest {
    private val jwtService = JwtService(AppConfig.load().auth)
    private val pragun = User(id = UUID.randomUUID(), email = "pragun@example.in", googleId = "g-704")
    private val other = User(id = UUID.randomUUID(), email = "someone@example.in", googleId = "g-705")
    private val consents = InMemoryAiConsentRepository()
    private val calls = InMemoryAiCallLogRepository()
    private val costs = AiCostGuard(InMemoryAiSpendLedger(), 2_000_000)
    private val record = AiEvalRecord("categorization", "anthropic", FakeAiProvider.MODEL, EchoTask.promptVersion, AiEvalAnswers.RECORDED_SYNTHETIC,
        "2026-10-17", 288, mapOf("accuracy" to 0.9), mapOf("accuracy" to 0.9))

    private fun service(on: Boolean) = AiFeaturesService(
        AiGateway(if (on) listOf(AiRoute.of(AiFeature.CATEGORIZATION, FakeAiProvider(), FakeAiProvider.MODEL)) else emptyList(), costs, calls, AiConsents(consents)),
        mapOf(AiFeature.CATEGORIZATION to EchoTask, AiFeature.ASK to dev.adminos.api.domain.ask.AskPlanTask,
            AiFeature.MESSAGES to dev.adminos.api.domain.ingestion.fallback.ReadMessageTask), consents, calls, AiEvalRecords(listOf(record)), costs,
        AuditService(InMemoryAuditRepository(), CoroutineScope(Dispatchers.Unconfined))
    )

    private fun routeTest(on: Boolean = true, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            install(OriginCheck) { allowedOrigin = "http://localhost:3000" } // the app-wide CSRF check (AOS-618)
            routing { route("/api/v1") { aiRoutes(service(on)) } }
        }
        block()
    }

    private data class Reply(val status: HttpStatusCode, val body: JsonObject?, val text: String)

    private suspend fun ApplicationTestBuilder.call(
        method: HttpMethod, path: String, json: String? = null, token: String? = jwtService.generateAccessToken(pragun),
        site: String? = "same-origin", origin: String? = null
    ): Reply {
        val res = client.request("/api/v1$path") {
            this.method = method
            token?.let { cookie(ACCESS_TOKEN_COOKIE, it) }
            site?.let { header("Sec-Fetch-Site", it) }
            origin?.let { header("Origin", it) }
            json?.let { contentType(ContentType.Application.Json); setBody(it) }
        }
        val text = res.bodyAsText()
        return Reply(res.status, if (text.isBlank()) null else Json.parseToJsonElement(text).jsonObject, text)
    }

    private fun Reply.code() = body?.get("error")?.jsonObject?.get("code")?.jsonPrimitive?.content
    private fun Reply.feature() = body!!.getValue("data").jsonObject

    private val on = """{"enabled":true}"""

    @Test
    fun `the list shows each feature off, with its notice, where data goes and the cap`() = routeTest {
        val r = call(HttpMethod.Get, "/ai/features")
        assertEquals(HttpStatusCode.OK, r.status)
        val data = r.body!!.getValue("data").jsonObject
        assertEquals("2.00", data["monthlyCapUsd"]?.jsonPrimitive?.content)
        val f = data.getValue("features").jsonArray.map { it.jsonObject }.single { it["key"]?.jsonPrimitive?.content == "categorization" }
        assertEquals("categorization", f["key"]?.jsonPrimitive?.content)
        assertEquals(false, f["enabled"]?.jsonPrimitive?.boolean)
        assertEquals(true, f["available"]?.jsonPrimitive?.boolean)
        assertEquals("cloud", f["locality"]?.jsonPrimitive?.content)
        assertEquals("anthropic", f["provider"]?.jsonPrimitive?.content)
        assertEquals("recorded-synthetic", f.getValue("evaluation").jsonObject["answers"]?.jsonPrimitive?.content)
        assertEquals("0.00", f["spentThisMonthUsd"]?.jsonPrimitive?.content)
        assertEquals(EchoTask.notice.sends, f.getValue("sends").jsonArray.map { it.jsonPrimitive.content })
        assertFalse("apiKey" in r.text || "ANTHROPIC" in r.text, "no secret or config name is ever returned")
    }

    @Test
    fun `turning it on and off is the user's own choice`() = routeTest {
        val turnedOn = call(HttpMethod.Patch, "/ai/features/categorization", on)
        assertEquals(HttpStatusCode.OK, turnedOn.status)
        assertEquals(true, turnedOn.feature()["enabled"]?.jsonPrimitive?.boolean)
        assertTrue(runBlocking { AiConsents(consents).allows(pragun.id, AiFeature.CATEGORIZATION) })

        val othersView = call(HttpMethod.Get, "/ai/features", token = jwtService.generateAccessToken(other))
        assertEquals(false, othersView.body!!.getValue("data").jsonObject.getValue("features").jsonArray.map { it.jsonObject }.single { it["key"]?.jsonPrimitive?.content == "categorization" }["enabled"]?.jsonPrimitive?.boolean,
            "another user's consent is never shown or applied")

        val off = call(HttpMethod.Patch, "/ai/features/categorization", """{"enabled":false}""")
        assertEquals(false, off.feature()["enabled"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `a feature the server doesn't run can't be turned on`() = routeTest(on = false) {
        val r = call(HttpMethod.Patch, "/ai/features/categorization", on)
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertEquals(NOT_AVAILABLE, r.code())
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Patch, "/ai/features/categorization", """{"enabled":false}""").status)
    }

    // ── Attacks (D-100) ──

    @Test
    fun `signed out, or with a forged or expired token, nothing is read or changed`() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/ai/features", token = null).status)
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Patch, "/ai/features/categorization", on, token = null).status)
        val forged = jwtService.generateAccessToken(pragun).dropLast(4) + "AAAA"
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Patch, "/ai/features/categorization", on, token = forged).status)
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/ai/features", token = "not.a.jwt").status)
        assertFalse(runBlocking { AiConsents(consents).allows(pragun.id, AiFeature.CATEGORIZATION) })
    }

    @Test
    fun `a cross-site page can't turn a feature on with the user's cookie`() = routeTest {
        for ((site, origin) in listOf<Pair<String?, String?>>("cross-site" to null, "same-site" to null, null to "https://evil.example", null to null)) {
            val r = call(HttpMethod.Patch, "/ai/features/categorization", on, site = site, origin = origin)
            assertEquals(HttpStatusCode.Forbidden, r.status, "$site / $origin")
            assertEquals(CROSS_SITE_REFUSED.code, r.code())
        }
        assertFalse(runBlocking { AiConsents(consents).allows(pragun.id, AiFeature.CATEGORIZATION) })
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Patch, "/ai/features/categorization", on, site = null, origin = "http://localhost:3000").status)
    }

    @Test
    fun `unknown, oversized or path-trick feature keys are 404 and never echoed`() = routeTest {
        for (key in listOf("nope", "CATEGORIZATION", "..%2F..%2Fadmin", "%3Cscript%3Ealert(1)%3C%2Fscript%3E", "x".repeat(5000))) {
            val r = call(HttpMethod.Patch, "/ai/features/$key", on)
            assertEquals(HttpStatusCode.NotFound, r.status, key.take(30))
            assertFalse("script" in r.text || "admin" in r.text || "xxxxx" in r.text, "the key isn't reflected")
        }
    }

    @Test
    fun `malformed or hostile bodies are 400 and change nothing`() = routeTest {
        for (body in listOf("", "not json", """{"enabled":"yes"}""", """{"enabled":null}""", """{}""", """[true]""", """{"enabled":1}""")) {
            val r = call(HttpMethod.Patch, "/ai/features/categorization", body)
            assertEquals(HttpStatusCode.BadRequest, r.status, body)
        }
        assertFalse(runBlocking { AiConsents(consents).allows(pragun.id, AiFeature.CATEGORIZATION) })
    }
}
