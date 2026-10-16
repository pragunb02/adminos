package dev.adminos.api.domain.notifications.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PushModelTest {
    @Test
    fun `endpoints of the Apple, Google, Mozilla and Microsoft push services are accepted`() {
        listOf(
            "https://web.push.apple.com/QGabc",
            "https://fcm.googleapis.com/fcm/send/abc:def",
            "https://updates.push.services.mozilla.com/wpush/v2/abc",
            "https://wns2-par02p.notify.windows.com/w/?token=abc",
            "https://web.push.apple.com:443/QGabc",
        ).forEach { PushEndpoint(it) }
        assertEquals("https://web.push.apple.com", PushEndpoint("https://web.push.apple.com/QGabc").origin)
    }

    @Test
    fun `anything else is refused, so the server never posts to an arbitrary URL`() {
        listOf(
            "http://web.push.apple.com/QG" to "https",
            "https://example.com/push" to "known push service",
            "https://push.apple.com.evil.example/x" to "known push service",
            "https://evilpush.apple.com/x" to "known push service",
            "https://web.push.apple.com:8443/x" to "default https port",
            "https://user:pw@web.push.apple.com/x" to "credentials",
            "https:///nohost" to "host",
            "not a url with spaces" to "URL",
            "https://web.push.apple.com/" + "x".repeat(2100) to "too long",
        ).forEach { (url, why) ->
            val e = assertFailsWith<IllegalArgumentException>(url) { PushEndpoint(url) }
            assert(e.message.orEmpty().contains(why)) { "$url → ${e.message}" }
        }
    }

    @Test
    fun `an endpoint prints only its origin, keys print nothing`() {
        assertEquals("https://web.push.apple.com", PushEndpoint("https://web.push.apple.com/QGsecret-device-id").toString())
        assertFalse(PushFixtures.UA_PUBLIC in PushFixtures.keys.toString())
    }

    @Test
    fun `keys must be a P-256 point and a 16-byte secret in URL-safe base64`() {
        assertEquals(65, PushFixtures.keys.p256dhBytes().size)
        assertEquals(16, PushFixtures.keys.authBytes().size)
        PushKeys(PushFixtures.UA_PUBLIC + "=", PushFixtures.AUTH + "==") // padding is tolerated
        assertFailsWith<IllegalArgumentException> { PushKeys("AAAA", PushFixtures.AUTH) }
        assertFailsWith<IllegalArgumentException> { PushKeys(PushFixtures.UA_PUBLIC, "AAAA") }
        assertFailsWith<IllegalArgumentException> { PushKeys("not base64 !!", PushFixtures.AUTH) }
        assertFailsWith<IllegalArgumentException> { PushKeys(PushFixtures.UA_PUBLIC, "%%%") }
    }

    @Test
    fun `a push message is a titled in-app path with a sane lifetime`() {
        PushMessage("Bill due", "HDFC", url = "/bills")
        assertFailsWith<IllegalArgumentException> { PushMessage(" ", "x") }
        assertFailsWith<IllegalArgumentException> { PushMessage("t", "x", url = "https://evil.example") }
        assertFailsWith<IllegalArgumentException> { PushMessage("t", "x", url = "//evil.example") }
        assertFailsWith<IllegalArgumentException> { PushMessage("t", "x", ttlSeconds = -1) }
        assertFailsWith<IllegalArgumentException> { PushSubscription(userId = java.util.UUID.randomUUID(), endpoint = PushFixtures.endpoint(), keys = PushFixtures.keys, label = "x".repeat(101)) }
    }
}
