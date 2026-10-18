package dev.adminos.api.domain.notifications.push

import dev.adminos.api.config.AppConfig
import dev.adminos.api.domain.identity.JwtService
import dev.adminos.api.domain.identity.User
import dev.adminos.api.infrastructure.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** /api/v1/push (AOS-510): status, turn on/off, test; validation and "not set up" errors. */
class PushRoutesTest {
    private val jwtService = JwtService(AppConfig.load().auth)
    private val user = User(id = UUID.randomUUID(), email = "owner@example.com", googleId = "g-510")
    private val jwt = jwtService.generateAccessToken(user)
    private val repo = InMemoryPushSubscriptionRepository()
    private val sender = ScriptedPushSender()
    private val enabled = PushSubscriptionService(repo, WebPushChannel(repo, sender), "PUBLIC-KEY")

    private fun routeTest(service: PushSubscriptionService = enabled, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            routing { route("/api/v1") { pushRoutes(service) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, path: String, json: String? = null, auth: Boolean = true): Pair<HttpStatusCode, JsonObject?> {
        val res = client.request("/api/v1$path") {
            this.method = method
            if (auth) cookie(ACCESS_TOKEN_COOKIE, jwt)
            json?.let { contentType(ContentType.Application.Json); setBody(it) }
        }
        val text = res.bodyAsText()
        return res.status to (if (text.isBlank()) null else Json.parseToJsonElement(text).jsonObject)
    }
    private fun JsonObject.data() = this["data"]!!
    private fun JsonObject.code() = this["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    private val endpoint = "https://web.push.apple.com/QGdevice-1"
    private val subscribe = """{"endpoint":"$endpoint","keys":{"p256dh":"${PushFixtures.UA_PUBLIC}","auth":"${PushFixtures.AUTH}"},"label":"iPhone · Safari"}"""

    @Test
    fun `status gives the public key and this user's devices`() = routeTest {
        runBlocking { repo.saved(PushFixtures.subscription(user.id, label = "iPhone · Safari")) }
        val (status, body) = call(HttpMethod.Get, "/push")
        assertEquals(HttpStatusCode.OK, status)
        val data = body!!.data().jsonObject
        assertEquals(true, data["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("PUBLIC-KEY", data["publicKey"]!!.jsonPrimitive.content)
        val device = data["devices"]!!.jsonArray.single().jsonObject
        assertEquals("iPhone · Safari", device["label"]!!.jsonPrimitive.content)
        assertTrue("endpoint" !in device, "endpoints are never sent back")
    }

    @Test
    fun `turn on, test, then turn off`() = routeTest {
        val (created, body) = call(HttpMethod.Post, "/push/subscriptions", subscribe)
        assertEquals(HttpStatusCode.Created, created)
        assertEquals("iPhone · Safari", body!!.data().jsonObject["label"]!!.jsonPrimitive.content)

        val (tested, result) = call(HttpMethod.Post, "/push/test")
        assertEquals(HttpStatusCode.OK, tested)
        assertEquals(true, result!!.data().jsonObject["delivered"]!!.jsonPrimitive.boolean)
        assertEquals(1, sender.sent.size)

        val (off, removed) = call(HttpMethod.Delete, "/push/subscriptions", """{"endpoint":"$endpoint"}""")
        assertEquals(HttpStatusCode.OK, off)
        assertEquals(true, removed!!.data().jsonObject["removed"]!!.jsonPrimitive.boolean)
        val (again, error) = call(HttpMethod.Delete, "/push/subscriptions", """{"endpoint":"$endpoint"}""")
        assertEquals(HttpStatusCode.NotFound, again)
        assertEquals("PUSH_002", error!!.code())
    }

    @Test
    fun `registering a device another account already has is a 409 and changes nothing (AOS-627)`() = routeTest {
        val owner = UUID.randomUUID()
        val theirs = runBlocking { repo.saved(PushSubscription(userId = owner, endpoint = PushEndpoint(endpoint), keys = PushFixtures.keys, label = "Owner")) }

        val (status, body) = call(HttpMethod.Post, "/push/subscriptions", subscribe)

        assertEquals(HttpStatusCode.Conflict, status)
        assertEquals("PUSH_003", body!!.code())
        val kept = runBlocking { repo.findByUserId(owner) }.single()
        assertEquals(theirs.id, kept.id)
        assertEquals("Owner", kept.label)
        assertTrue(runBlocking { repo.findByUserId(user.id) }.isEmpty())
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Delete, "/push/subscriptions", """{"endpoint":"$endpoint"}""").first,
            "and the caller can't turn it off either")
    }

    @Test
    fun `a subscription for an unknown push service or with bad keys is a 400 naming the field`() = routeTest {
        val (badHost, e1) = call(HttpMethod.Post, "/push/subscriptions", subscribe.replace("web.push.apple.com", "evil.example"))
        assertEquals(HttpStatusCode.BadRequest, badHost)
        assertTrue("endpoint" in e1!!["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        val (badKey, e2) = call(HttpMethod.Post, "/push/subscriptions", subscribe.replace(PushFixtures.AUTH, "AAAA"))
        assertEquals(HttpStatusCode.BadRequest, badKey)
        assertTrue("keys.auth" in e2!!["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/push/subscriptions", """{"endpoint":"x"}""").first)
    }

    @Test
    fun `without server keys turning push on or testing is a 503 the page can explain`() = routeTest(PushSubscriptionService(repo, null, null)) {
        val (status, body) = call(HttpMethod.Get, "/push")
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(false, body!!.data().jsonObject["enabled"]!!.jsonPrimitive.boolean)
        val (on, e1) = call(HttpMethod.Post, "/push/subscriptions", subscribe)
        assertEquals(HttpStatusCode.ServiceUnavailable, on)
        assertEquals("PUSH_001", e1!!.code())
        assertEquals(HttpStatusCode.ServiceUnavailable, call(HttpMethod.Post, "/push/test").first)
    }

    @Test
    fun `signed-out calls are refused`() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/push", auth = false).first)
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Post, "/push/subscriptions", subscribe, auth = false).first)
    }
}
