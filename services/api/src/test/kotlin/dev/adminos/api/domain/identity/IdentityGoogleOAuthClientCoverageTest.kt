package dev.adminos.api.domain.identity

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Disabled
import kotlin.test.*

/** AOS-312: GoogleOAuthClient against a local fake Google (no real network). */
class IdentityGoogleOAuthClientCoverageTest {

    private val google = FakeGoogleServer()
    private val client = google.client()

    @AfterTest
    fun stop() = google.close()

    @Test
    fun `exchanging a code returns google's tokens and the user's profile`() = runBlocking<Unit> {
        val (tokens, user) = client.exchangeCodeForTokens("4/0AbCdEf", "http://localhost:3000/auth/callback")

        assertEquals("ya29.a0-test-access", tokens.accessToken)
        assertEquals("1//0g-test-refresh", tokens.refreshToken)
        assertEquals(3599, tokens.expiresIn)
        assertEquals("109876543210987654321", user.sub)
        assertEquals("pragun.b@gmail.com", user.email)
        assertEquals(true, user.emailVerified)
    }

    @Test
    fun `the token request is an authorization_code grant with the client credentials`() = runBlocking<Unit> {
        client.exchangeCodeForTokens("4/0AbCdEf", "http://localhost:3000/auth/callback")

        val form = google.lastTokenForm()
        assertEquals("4/0AbCdEf", form["code"])
        assertEquals("test-client-id.apps.googleusercontent.com", form["client_id"])
        assertEquals("test-client-secret", form["client_secret"])
        assertEquals("http://localhost:3000/auth/callback", form["redirect_uri"])
        assertEquals("authorization_code", form["grant_type"])
    }

    @Test
    fun `userinfo is fetched with the access token google returned`() = runBlocking<Unit> {
        client.exchangeCodeForTokens("4/0AbCdEf", "http://localhost:3000/auth/callback")

        assertEquals(listOf<String?>("Bearer ya29.a0-test-access"), google.userInfoAuthorizations.toList())
    }

    @Test
    fun `unknown fields in google's responses are ignored`() = runBlocking<Unit> {
        google.userInfoBody = """{"sub":"1","email":"a@gmail.com","locale":"en-IN","hd":"example.com"}"""

        val user = client.exchangeCode("4/0AbCdEf", "http://localhost:3000/auth/callback")

        assertEquals("a@gmail.com", user.email)
        assertNull(user.name)
        assertNull(user.emailVerified)
    }

    @Test
    fun `a rejected code fails the exchange and never calls userinfo`() = runBlocking<Unit> {
        google.tokenStatus = 400
        google.tokenBody = """{"error":"invalid_grant","error_description":"Bad Request"}"""

        val e = assertFailsWith<RuntimeException> { client.exchangeCode("4/expired", "http://localhost:3000/auth/callback") }

        assertTrue("token exchange failed" in e.message.orEmpty())
        assertTrue(google.userInfoAuthorizations.isEmpty())
    }

    @Test
    fun `a failed userinfo call fails the exchange`() = runBlocking<Unit> {
        google.userInfoStatus = 401
        google.userInfoBody = """{"error":"invalid_token"}"""

        val e = assertFailsWith<RuntimeException> { client.exchangeCode("4/0AbCdEf", "http://localhost:3000/auth/callback") }

        assertTrue("userinfo fetch failed" in e.message.orEmpty())
    }

    @Test
    fun `code and redirect uri are form-url-encoded in the token request`() = runBlocking<Unit> {
        client.exchangeCodeForTokens("4/0Ab+c%2F&grant_type=evil", "http://localhost:3000/auth/callback?x=1&y=2")

        val form = google.lastTokenForm()
        assertEquals("4/0Ab+c%2F&grant_type=evil", form["code"])
        assertEquals("http://localhost:3000/auth/callback?x=1&y=2", form["redirect_uri"])
        assertEquals("authorization_code", form["grant_type"])
    }
}
