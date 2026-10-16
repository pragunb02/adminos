package dev.adminos.api.domain.notifications

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** JSON contract of the notification DTOs (AOS-312): optional request fields, required response fields. */
class NotificationsDtoContractCoverageTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val compact = Json { encodeDefaults = false }

    private val fullRequest = UpdatePreferencesRequest(
        weeklyBriefing = true, briefingDay = "tue", briefingTime = "07:45", billReminders = true,
        billReminderDays = listOf(5, 1), anomalyAlerts = true, subscriptionFlags = false, emailNotifications = true,
        pushNotifications = false, quietHoursStart = "22:30", quietHoursEnd = "07:00"
    )

    private val prefsResponse = NotificationPreferencesResponse(
        id = "p-1", userId = "u-1", weeklyBriefing = true, briefingDay = "mon", briefingTime = "08:00", billReminders = true,
        billReminderDays = listOf(3, 1), anomalyAlerts = true, subscriptionFlags = true, emailNotifications = true,
        pushNotifications = true, quietHoursStart = "22:00", quietHoursEnd = "08:00",
        createdAt = "2026-10-16T00:00:00Z", updatedAt = "2026-10-16T00:00:00Z"
    )

    private val notifResponse = NotificationResponse(
        id = "n-1", type = "bill_reminder", title = "Bill due in 3 days", body = "HDFC ₹4560.00",
        channel = "push", status = "sent", sentAt = null, createdAt = "2026-10-16T00:00:00Z"
    )

    @Test
    fun `an empty PATCH body decodes to a request with every field null`() {
        assertEquals(UpdatePreferencesRequest(), json.decodeFromString<UpdatePreferencesRequest>("{}"))
    }

    @Test
    fun `a full PATCH body decodes every field`() {
        val body = """{"weeklyBriefing":true,"briefingDay":"tue","briefingTime":"07:45","billReminders":true,
            "billReminderDays":[5,1],"anomalyAlerts":true,"subscriptionFlags":false,"emailNotifications":true,
            "pushNotifications":false,"quietHoursStart":"22:30","quietHoursEnd":"07:00"}"""

        assertEquals(fullRequest, json.decodeFromString<UpdatePreferencesRequest>(body))
    }

    @Test
    fun `a request round-trips through JSON`() {
        assertEquals(fullRequest, json.decodeFromString<UpdatePreferencesRequest>(json.encodeToString(fullRequest)))
    }

    @Test
    fun `without encodeDefaults a request omits null fields`() {
        assertEquals("{}", compact.encodeToString(UpdatePreferencesRequest()))
        assertEquals(11, compact.parseToJsonElement(compact.encodeToString(fullRequest)).jsonObject.size)
    }

    @Test
    fun `with encodeDefaults a request writes null fields explicitly`() {
        assertEquals(11, json.parseToJsonElement(json.encodeToString(UpdatePreferencesRequest())).jsonObject.size)
    }

    @Test
    fun `omitting only the first or only the last request field leaves just that one null`() {
        val noFirst = UpdatePreferencesRequest(
            briefingDay = "tue", briefingTime = "07:45", billReminders = true, billReminderDays = listOf(5, 1),
            anomalyAlerts = true, subscriptionFlags = false, emailNotifications = true, pushNotifications = false,
            quietHoursStart = "22:30", quietHoursEnd = "07:00"
        )
        val noLast = UpdatePreferencesRequest(
            weeklyBriefing = true, briefingDay = "tue", briefingTime = "07:45", billReminders = true,
            billReminderDays = listOf(5, 1), anomalyAlerts = true, subscriptionFlags = false, emailNotifications = true,
            pushNotifications = false, quietHoursStart = "22:30"
        )

        assertEquals(fullRequest.copy(weeklyBriefing = null), noFirst)
        assertEquals(fullRequest.copy(quietHoursEnd = null), noLast)
    }

    @Test
    fun `a preferences response round-trips through JSON`() {
        assertEquals(prefsResponse, json.decodeFromString<NotificationPreferencesResponse>(json.encodeToString(prefsResponse)))
    }

    @Test
    fun `a preferences response missing a required field is rejected`() {
        val e = assertFailsWith<SerializationException> { json.decodeFromString<NotificationPreferencesResponse>("""{"id":"p-1"}""") }

        assertTrue(e.message!!.contains("userId"))
    }

    @Test
    fun `a notification response round-trips with a null sentAt`() {
        val encoded = json.encodeToString(notifResponse)

        assertTrue(encoded.contains("\"sentAt\":null"))
        assertEquals(notifResponse, json.decodeFromString<NotificationResponse>(encoded))
    }

    @Test
    fun `a notification response missing a required field is rejected`() {
        assertFailsWith<SerializationException> { json.decodeFromString<NotificationResponse>("""{"id":"n-1","type":"general"}""") }
    }
}
