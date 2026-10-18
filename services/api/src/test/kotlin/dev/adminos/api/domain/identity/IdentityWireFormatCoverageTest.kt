package dev.adminos.api.domain.identity

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.*

/**
 * AOS-312: wire-format checks for a DTO. Each value must survive a JSON round trip whether or not
 * defaults are written, and a body missing a required field must be rejected.
 */
internal fun <T> assertWireFormat(serializer: KSerializer<T>, minimal: T, full: T, missingRequired: String?) {
    val withDefaults = Json { encodeDefaults = true }
    val withoutDefaults = Json { encodeDefaults = false }
    for (json in listOf(withDefaults, withoutDefaults)) {
        for (value in listOf(minimal, full)) {
            assertEquals(value, json.decodeFromString(serializer, json.encodeToString(serializer, value)))
        }
    }
    if (missingRequired != null) {
        assertFailsWith<SerializationException> { withDefaults.decodeFromString(serializer, missingRequired) }
    }
}

class IdentityWireFormatCoverageTest {

    private val user = UserResponse("6f1c0f3e-0000-4000-8000-000000000001", "owner@example.com", onboardingStatus = "started", role = "owner")

    @Test
    fun `google login request round-trips and needs both fields`() =
        assertWireFormat(GoogleLoginRequest.serializer(), GoogleLoginRequest("4/0Ab", "http://localhost:3000/auth/callback"),
            GoogleLoginRequest("4/0Ab+x", "https://adminos.example.in/auth/callback"), """{"code":"4/0Ab"}""")

    @Test
    fun `user response round-trips with and without name and avatar`() =
        assertWireFormat(UserResponse.serializer(), user, user.copy(name = "Owner", avatarUrl = "https://lh3.googleusercontent.com/a/p"),
            """{"id":"x","email":"a@b.c","onboardingStatus":"started"}""")

    @Test
    fun `auth token response round-trips and needs the user`() =
        assertWireFormat(AuthTokenResponse.serializer(), AuthTokenResponse(900, user), AuthTokenResponse(3600, user.copy(name = "P")), """{"expiresIn":900}""")

    @Test
    fun `access token response round-trips and needs expiresIn`() =
        assertWireFormat(AccessTokenResponse.serializer(), AccessTokenResponse(900), AccessTokenResponse(3600), "{}")

    @Test
    fun `session response round-trips with and without device details`() =
        assertWireFormat(SessionResponse.serializer(), SessionResponse("s-1", null, null, null, "2026-10-16T06:00:00Z"),
            SessionResponse("s-2", "203.0.113.7", "Mozilla/5.0", "2026-10-16T07:00:00Z", "2026-10-16T06:00:00Z"), """{"id":"s-1"}""")

    @Test
    fun `google token response round-trips with only the access token or with every field`() =
        assertWireFormat(GoogleTokenResponse.serializer(), GoogleTokenResponse("ya29.a"),
            GoogleTokenResponse("ya29.b", "eyJ.id", "Bearer", 3599, "1//0g", "openid email"), """{"id_token":"eyJ"}""")

    @Test
    fun `google user info round-trips with and without the optional profile fields`() =
        assertWireFormat(GoogleUserInfo.serializer(), GoogleUserInfo("1098", "a@example.com"),
            GoogleUserInfo("1099", "b@example.com", "Owner", "https://lh3/p", true), """{"sub":"1098"}""")

    @Test
    fun `device token request defaults to the sms scope and needs a name`() {
        assertWireFormat(CreateDeviceTokenRequest.serializer(), CreateDeviceTokenRequest("My Mac"),
            CreateDeviceTokenRequest("My Mac", listOf("ingest:sms", "other")), """{"scopes":[]}""")
        assertEquals(listOf(Scopes.INGEST_SMS), Json.decodeFromString(CreateDeviceTokenRequest.serializer(), """{"name":"My Mac"}""").scopes)
    }

    @Test
    fun `device token response round-trips with and without the one-time token`() {
        val minimal = DeviceTokenResponse("t-1", "My Mac", listOf("ingest:sms"), "2026-10-16T06:00:00Z", "2027-10-16T06:00:00Z", null, null)
        assertWireFormat(DeviceTokenResponse.serializer(), minimal,
            minimal.copy(lastUsedAt = "2026-10-16T07:00:00Z", revokedAt = "2026-10-16T08:00:00Z", token = "eyJ.device"), """{"id":"t-1"}""")
    }
}
