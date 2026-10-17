package dev.adminos.api.domain.identity

import dev.adminos.api.domain.ingestion.connection.GmailAccessException
import dev.adminos.api.domain.ingestion.connection.runtimeToken
import dev.adminos.api.domain.scheduling.TestClock
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.*

/** AOS-405: refreshing a Gmail access token at Google's token endpoint (a local fake). */
class GoogleTokenRefreshTest {

    private val google = FakeGoogleServer()
    private val now = Instant.parse("2026-10-17T06:00:00Z")
    private val client = GoogleOAuthClient(identityTestAuthConfig(), google.tokenUrl, google.userInfoUrl, TestClock(now))

    @AfterTest
    fun stop() = google.close()

    @Test
    fun `a refresh is a refresh_token grant with the client credentials`() = runBlocking<Unit> {
        val refresh = runtimeToken("1//")

        client.refresh(refresh)

        val form = google.lastTokenForm()
        assertEquals("refresh_token", form["grant_type"])
        assertEquals(refresh, form["refresh_token"])
        assertEquals("test-client-id.apps.googleusercontent.com", form["client_id"])
        assertTrue(form.containsKey("client_secret"))
    }

    @Test
    fun `the new access token expires expires_in seconds from now`() = runBlocking<Unit> {
        val access = runtimeToken()
        google.tokenBody = """{"access_token":"$access","expires_in":3599,"token_type":"Bearer"}"""

        val fresh = client.refresh(runtimeToken("1//"))

        assertEquals(access, fresh.accessToken)
        assertEquals(now.plusSeconds(3599), fresh.expiresAt)
    }

    @Test
    fun `without expires_in the expiry is unknown`() = runBlocking<Unit> {
        google.tokenBody = """{"access_token":"${runtimeToken()}"}"""

        assertNull(client.refresh(runtimeToken("1//")).expiresAt)
    }

    @Test
    fun `invalid_grant means the grant was revoked`() = runBlocking<Unit> {
        google.tokenStatus = 400
        google.tokenBody = """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}"""

        assertFailsWith<GmailAccessException.Revoked> { client.refresh(runtimeToken("1//")) }
    }

    @Test
    fun `other token errors are unavailable and never quote google's body`() = runBlocking<Unit> {
        google.tokenStatus = 400
        google.tokenBody = """{"error":"invalid_client","error_description":"leaky detail"}"""
        val e = assertFailsWith<GmailAccessException.Unavailable> { client.refresh(runtimeToken("1//")) }
        assertFalse("leaky detail" in e.message.orEmpty())

        google.tokenStatus = 503
        assertFailsWith<GmailAccessException.Unavailable> { client.refresh(runtimeToken("1//")) }
    }

    @Test
    fun `an unreadable or blank answer is unavailable`() = runBlocking<Unit> {
        google.tokenBody = """{"token_type":"Bearer"}"""
        assertFailsWith<GmailAccessException.Unavailable> { client.refresh(runtimeToken("1//")) }

        google.tokenBody = """{"access_token":" "}"""
        assertFailsWith<GmailAccessException.Unavailable> { client.refresh(runtimeToken("1//")) }
    }

    @Test
    fun `an unreachable token endpoint is unavailable`() = runBlocking<Unit> {
        google.close()

        assertFailsWith<GmailAccessException.Unavailable> { client.refresh(runtimeToken("1//")) }
    }

    @Test
    fun `without a google client nothing is sent`() = runBlocking<Unit> {
        val unconfigured = GoogleOAuthClient(identityTestAuthConfig().copy(googleClientId = ""), google.tokenUrl, google.userInfoUrl)

        assertFailsWith<GmailAccessException.Unavailable> { unconfigured.refresh(runtimeToken("1//")) }
        assertTrue(google.tokenRequests.isEmpty())
    }
}
