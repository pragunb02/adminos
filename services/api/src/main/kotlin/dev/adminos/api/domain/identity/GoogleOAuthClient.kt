package dev.adminos.api.domain.identity

import dev.adminos.api.config.AuthConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

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
    private val userInfoUrl: String = "https://www.googleapis.com/oauth2/v3/userinfo"
) {

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
