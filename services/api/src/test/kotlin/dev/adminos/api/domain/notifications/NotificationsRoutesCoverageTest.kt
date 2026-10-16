package dev.adminos.api.domain.notifications

import dev.adminos.api.config.AppConfig
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.koin.dsl.module
import org.koin.ktor.plugin.KoinIsolated
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** /api/v1/users/me/notification-preferences and /api/v1/notifications (AOS-312). */
class NotificationsRoutesCoverageTest {
    private val jwtService = JwtService(AppConfig.load().auth)
    private val user = User(id = UUID.randomUUID(), email = "pragun@example.in", googleId = "g-312", role = UserRole.OWNER)
    private val jwt = jwtService.generateAccessToken(user)
    private val prefsRepo = InMemoryNotificationPreferencesRepository()
    private val notifRepo = InMemoryNotificationRepository()

    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            routing { route("/api/v1") { notificationPreferencesRoutes(prefsRepo); notificationListRoutes(notifRepo) } }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, path: String, json: String? = null, auth: Boolean = true): Pair<HttpStatusCode, JsonObject> {
        val res = client.request(path) {
            this.method = method
            if (auth) cookie(ACCESS_TOKEN_COOKIE, jwt)
            json?.let { contentType(ContentType.Application.Json); setBody(it) }
        }
        return res.status to Json.parseToJsonElement(res.bodyAsText()).jsonObject
    }
    private fun JsonObject.data() = this["data"]!!.jsonObject
    private fun JsonObject.errorCode() = this["error"]!!.jsonObject["code"]!!.jsonPrimitive.content
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private val prefsPath = "/api/v1/users/me/notification-preferences"

    @Test
    fun `GET preferences creates and returns defaults for a new user`() = routeTest {
        val (status, body) = call(HttpMethod.Get, prefsPath)

        assertEquals(HttpStatusCode.OK, status)
        val d = body.data()
        assertEquals(user.id.toString(), d.str("userId"))
        assertEquals("mon", d.str("briefingDay"))
        assertEquals("22:00", d.str("quietHoursStart"))
        assertEquals(listOf(3, 1), d["billReminderDays"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertNotNull(prefsRepo.findByUserId(user.id))
    }

    @Test
    fun `GET preferences returns the stored preferences`() = routeTest {
        prefsRepo.save(NotificationPreferences(userId = user.id, weeklyBriefing = false, briefingDay = Weekday.SUN))

        val d = call(HttpMethod.Get, prefsPath).second.data()

        assertEquals("false", d.str("weeklyBriefing"))
        assertEquals("sun", d.str("briefingDay"))
    }

    @Test
    fun `GET preferences without a session is 401`() = routeTest {
        val (status, body) = call(HttpMethod.Get, prefsPath, auth = false)

        assertEquals(HttpStatusCode.Unauthorized, status)
        assertTrue(body.containsKey("error"))
    }

    @Test
    fun `PATCH with every field updates all of them`() = routeTest {
        val json = """{"weeklyBriefing":false,"briefingDay":"fri","briefingTime":"09:15","billReminders":false,
            "billReminderDays":[7,3,1],"anomalyAlerts":false,"subscriptionFlags":false,"emailNotifications":false,
            "pushNotifications":false,"quietHoursStart":"23:30","quietHoursEnd":"06:00"}"""

        val (status, body) = call(HttpMethod.Patch, prefsPath, json)

        assertEquals(HttpStatusCode.OK, status)
        val saved = prefsRepo.findByUserId(user.id)!!
        assertEquals(
            NotificationPreferences(
                id = saved.id, userId = user.id, weeklyBriefing = false, briefingDay = Weekday.FRI, briefingTime = LocalTime.of(9, 15),
                billReminders = false, billReminderDays = listOf(7, 3, 1), anomalyAlerts = false, subscriptionFlags = false,
                emailNotifications = false, pushNotifications = false, quietHoursStart = LocalTime.of(23, 30),
                quietHoursEnd = LocalTime.of(6, 0), createdAt = saved.createdAt, updatedAt = saved.updatedAt
            ),
            saved
        )
        assertEquals("fri", body.data().str("briefingDay"))
    }

    @Test
    fun `PATCH with an empty body keeps existing values`() = routeTest {
        val existing = prefsRepo.save(NotificationPreferences(userId = user.id, briefingDay = Weekday.WED, billReminderDays = listOf(5)))

        val (status, _) = call(HttpMethod.Patch, prefsPath, "{}")

        assertEquals(HttpStatusCode.OK, status)
        assertEquals(existing.copy(updatedAt = prefsRepo.findByUserId(user.id)!!.updatedAt), prefsRepo.findByUserId(user.id))
    }

    @Test
    fun `PATCH for a user without preferences starts from defaults`() = routeTest {
        val (status, body) = call(HttpMethod.Patch, prefsPath, """{"anomalyAlerts":false}""")

        assertEquals(HttpStatusCode.OK, status)
        assertEquals("false", body.data().str("anomalyAlerts"))
        assertEquals("true", body.data().str("billReminders"))
    }

    @Test
    fun `PATCH stores each reminder day once, largest first (AOS-613)`() = routeTest {
        val (status, body) = call(HttpMethod.Patch, prefsPath, """{"billReminderDays":[1,3,3,1]}""")

        assertEquals(HttpStatusCode.OK, status)
        assertEquals(listOf(3, 1), body.data()["billReminderDays"]!!.jsonArray.map { it.jsonPrimitive.int })
    }

    @Test
    fun `PATCH rejects a reminder day above 30`() = routeTest {
        val (status, body) = call(HttpMethod.Patch, prefsPath, """{"billReminderDays":[31]}""")

        assertEquals(HttpStatusCode.BadRequest, status)
        assertEquals("VALIDATION_001", body.errorCode())
    }

    @Test
    fun `PATCH rejects a reminder day of 0`() = routeTest {
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Patch, prefsPath, """{"billReminderDays":[0]}""").first)
    }

    @Test
    fun `PATCH rejects more than 5 reminder days`() = routeTest {
        val (status, body) = call(HttpMethod.Patch, prefsPath, """{"billReminderDays":[1,2,3,4,5,6]}""")

        assertEquals(HttpStatusCode.BadRequest, status)
        assertTrue(body["error"]!!.jsonObject.str("message").contains("Maximum 5"))
    }

    @Test
    fun `PATCH rejects an unknown briefing day`() = routeTest {
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Patch, prefsPath, """{"briefingDay":"funday"}""").first)
    }

    @Test
    fun `PATCH rejects a malformed quiet hours time`() = routeTest {
        val (status, body) = call(HttpMethod.Patch, prefsPath, """{"quietHoursEnd":"25:00"}""")

        assertEquals(HttpStatusCode.BadRequest, status)
        assertEquals("VALIDATION_002", body.errorCode())
    }

    @Test
    fun `PATCH without a session is 401`() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Patch, prefsPath, "{}", auth = false).first)
    }

    @Test
    fun `GET notifications lists only my notifications newest first`() = routeTest {
        val t = Instant.parse("2026-10-16T03:00:00Z")
        runBlocking {
            notifRepo.save(Notification(userId = user.id, type = NotificationType.BILL_REMINDER, title = "old", body = "b",
                                        channel = NotificationChannel.PUSH, createdAt = t))
            notifRepo.save(Notification(userId = user.id, type = NotificationType.ANOMALY, title = "new", body = "b",
                                        channel = NotificationChannel.IN_APP, status = NotificationStatus.SENT, sentAt = t, createdAt = t.plusSeconds(60)))
            notifRepo.save(Notification(userId = UUID.randomUUID(), type = NotificationType.GENERAL, title = "theirs", body = "b",
                                        channel = NotificationChannel.IN_APP))
        }

        val (status, body) = call(HttpMethod.Get, "/api/v1/notifications")

        assertEquals(HttpStatusCode.OK, status)
        val items = body["data"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("new", "old"), items.map { it.str("title") })
        assertEquals("anomaly", items[0].str("type"))
        assertEquals("2026-10-16T03:00:00Z", items[0].str("sentAt"))
    }

    @Test
    fun `GET notifications honours the limit parameter`() = routeTest {
        runBlocking { repeat(3) { notifRepo.save(Notification(userId = user.id, type = NotificationType.GENERAL, title = "n$it", body = "b", channel = NotificationChannel.IN_APP)) } }

        assertEquals(2, call(HttpMethod.Get, "/api/v1/notifications?limit=2").second["data"]!!.jsonArray.size)
    }

    @Test
    fun `GET notifications ignores a non-numeric limit`() = routeTest {
        runBlocking { repeat(3) { notifRepo.save(Notification(userId = user.id, type = NotificationType.GENERAL, title = "n$it", body = "b", channel = NotificationChannel.IN_APP)) } }

        assertEquals(3, call(HttpMethod.Get, "/api/v1/notifications?limit=lots").second["data"]!!.jsonArray.size)
    }

    @Test
    fun `GET notifications without a session is 401`() = routeTest {
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/api/v1/notifications", auth = false).first)
    }

    @Test
    fun `notificationModule wires its routes from Koin`() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            install(KoinIsolated) {
                modules(module {
                    single<NotificationPreferencesRepository> { prefsRepo }
                    single<NotificationRepository> { notifRepo }
                    single { dev.adminos.api.domain.notifications.push.PushSubscriptionService(
                        dev.adminos.api.domain.notifications.push.InMemoryPushSubscriptionRepository(), null, null) }
                })
            }
            configureSerialization(); configureRequestId(); configureErrorHandling(); configureAuthentication(jwtService)
            notificationModule()
        }

        val prefs = call(HttpMethod.Get, prefsPath)
        val list = call(HttpMethod.Get, "/api/v1/notifications")
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/api/v1/push").first)

        assertEquals(HttpStatusCode.OK, prefs.first)
        assertEquals(HttpStatusCode.OK, list.first)
        assertEquals(0, list.second["data"]!!.jsonArray.size)
    }
}
