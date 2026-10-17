package dev.adminos.api.domain.identity

import dev.adminos.api.config.AuthConfig
import dev.adminos.api.domain.ingestion.connection.GmailAccessException
import dev.adminos.api.domain.ingestion.connection.GmailTokenRefresher
import dev.adminos.api.domain.ingestion.connection.RefreshedAccessToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock

@Serializable
data class GoogleTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Int? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("scope") val scope: String? = null
)

@Serializable
data class GoogleUserInfo(
    val sub: String,
    val email: String,
    val name: String? = null,
    val picture: String? = null,
    @SerialName("email_verified") val emailVerified: Boolean? = null
)

/** [tokenUrl] and [userInfoUrl] default to Google's; tests point them at a local server (AOS-312). */
class GoogleOAuthClient(
    private val config: AuthConfig,
    private val tokenUrl: String = "https://oauth2.googleapis.com/token",
    private val userInfoUrl: String = "https://www.googleapis.com/oauth2/v3/userinfo",
    private val clock: Clock = Clock.systemUTC()
) : GmailTokenRefresher {

    private val httpClient = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Exchanges an OAuth authorization code for tokens and user info.
     * Returns both the token response (for storage) and user info.
     */
    suspend fun exchangeCodeForTokens(code: String, redirectUri: String): Pair<GoogleTokenResponse, GoogleUserInfo> = withContext(Dispatchers.IO) {
        // Every value is form-encoded (AOS-610): a raw '&', '+' or '%' in the code would corrupt the form or add fields.
        val tokenBody = formEncode(
            "code" to code,
            "client_id" to config.googleClientId,
            "client_secret" to config.googleClientSecret,
            "redirect_uri" to redirectUri,
            "grant_type" to "authorization_code"
        )

        val tokenRequest = HttpRequest.newBuilder()
            .uri(URI.create(tokenUrl))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(tokenBody))
            .build()

        val tokenResponse = httpClient.send(tokenRequest, HttpResponse.BodyHandlers.ofString())
        if (tokenResponse.statusCode() != 200) {
            throw RuntimeException("Google token exchange failed: ${tokenResponse.body()}")
        }

        val tokens = json.decodeFromString<GoogleTokenResponse>(tokenResponse.body())

        val userInfoRequest = HttpRequest.newBuilder()
            .uri(URI.create(userInfoUrl))
            .header("Authorization", "Bearer ${tokens.accessToken}")
            .GET()
            .build()

        val userInfoResponse = httpClient.send(userInfoRequest, HttpResponse.BodyHandlers.ofString())
        if (userInfoResponse.statusCode() != 200) {
            throw RuntimeException("Google userinfo fetch failed: ${userInfoResponse.body()}")
        }

        val userInfo = json.decodeFromString<GoogleUserInfo>(userInfoResponse.body())
        Pair(tokens, userInfo)
    }

    /**
     * Google's consent page for connecting Gmail (AOS-401): read-only Gmail plus the address,
     * offline access (a refresh token) and a fresh consent so Google always returns one.
     * Null when no Google client is configured.
     */
    fun gmailConsentUrl(redirectUri: String): String? {
        if (config.googleClientId.isBlank()) return null
        return "https://accounts.google.com/o/oauth2/v2/auth?" + formEncode(
            "client_id" to config.googleClientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to "openid email ${dev.adminos.api.domain.ingestion.connection.GMAIL_READONLY_SCOPE}",
            "access_type" to "offline",
            "prompt" to "consent",
            "include_granted_scopes" to "true"
        )
    }

    /**
     * A new access token from a refresh token (AOS-405), for the API's own Gmail calls.
     * Google's `invalid_grant` (revoked, or expired while the app is in testing) is [GmailAccessException.Revoked];
     * anything else is [GmailAccessException.Unavailable]. Response bodies are never quoted.
     */
    override suspend fun refresh(refreshToken: String): RefreshedAccessToken = withContext(Dispatchers.IO) {
        if (config.googleClientId.isBlank()) throw GmailAccessException.Unavailable()
        val request = HttpRequest.newBuilder()
            .uri(URI.create(tokenUrl))
            .timeout(java.time.Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(formEncode(
                "client_id" to config.googleClientId,
                "client_secret" to config.googleClientSecret,
                "refresh_token" to refreshToken,
                "grant_type" to "refresh_token"
            )))
            .build()
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            throw GmailAccessException.Unavailable(e)
        }
        when (response.statusCode()) {
            200 -> Unit
            400, 401 -> if ("invalid_grant" in response.body()) throw GmailAccessException.Revoked() else throw GmailAccessException.Unavailable()
            else -> throw GmailAccessException.Unavailable()
        }
        try {
            val tokens = json.decodeFromString<GoogleTokenResponse>(response.body())
            RefreshedAccessToken(tokens.accessToken, tokens.expiresIn?.let { clock.instant().plusSeconds(it.toLong()) })
        } catch (e: IllegalArgumentException) { // unreadable JSON (SerializationException) or a blank token
            throw GmailAccessException.Unavailable(e)
        }
    }

    /**
     * Legacy method — exchanges code and returns only user info.
     */
    suspend fun exchangeCode(code: String, redirectUri: String): GoogleUserInfo {
        val (_, userInfo) = exchangeCodeForTokens(code, redirectUri)
        return userInfo
    }
}

/** application/x-www-form-urlencoded body with every name and value encoded. */
internal fun formEncode(vararg fields: Pair<String, String>): String =
    fields.joinToString("&") { (k, v) ->
        java.net.URLEncoder.encode(k, Charsets.UTF_8) + "=" + java.net.URLEncoder.encode(v, Charsets.UTF_8)
    }
