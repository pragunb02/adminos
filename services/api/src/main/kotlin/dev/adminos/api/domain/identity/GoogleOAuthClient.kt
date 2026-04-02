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

class GoogleOAuthClient(private val config: AuthConfig) {

    private val httpClient = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun exchangeCode(code: String, redirectUri: String): GoogleUserInfo = withContext(Dispatchers.IO) {
        // Exchange auth code for tokens
        val tokenBody = "code=$code" +
            "&client_id=${config.googleClientId}" +
            "&client_secret=${config.googleClientSecret}" +
            "&redirect_uri=$redirectUri" +
            "&grant_type=authorization_code"

        val tokenRequest = HttpRequest.newBuilder()
            .uri(URI.create("https://oauth2.googleapis.com/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(tokenBody))
            .build()

        val tokenResponse = httpClient.send(tokenRequest, HttpResponse.BodyHandlers.ofString())
        if (tokenResponse.statusCode() != 200) {
            throw RuntimeException("Google token exchange failed: ${tokenResponse.body()}")
        }

        val tokens = json.decodeFromString<GoogleTokenResponse>(tokenResponse.body())

        // Fetch user info
        val userInfoRequest = HttpRequest.newBuilder()
            .uri(URI.create("https://www.googleapis.com/oauth2/v3/userinfo"))
            .header("Authorization", "Bearer ${tokens.accessToken}")
            .GET()
            .build()

        val userInfoResponse = httpClient.send(userInfoRequest, HttpResponse.BodyHandlers.ofString())
        if (userInfoResponse.statusCode() != 200) {
            throw RuntimeException("Google userinfo fetch failed: ${userInfoResponse.body()}")
        }

        json.decodeFromString<GoogleUserInfo>(userInfoResponse.body())
    }
}
