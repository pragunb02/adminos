package dev.adminos.api.infrastructure.google

import dev.adminos.api.domain.ingestion.connection.GmailPushApi
import dev.adminos.api.domain.ingestion.connection.GmailPushException
import dev.adminos.api.domain.ingestion.connection.WatchRegistration
import kotlinx.coroutines.future.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * Gmail's `users.watch` / `users.stop` over HTTPS (AOS-405). [baseUrl] is Google's; tests point it
 * at a local server. Errors carry the HTTP status only, never Google's response body (D-59).
 *
 * The watch covers the INBOX label only: Gmail then publishes far fewer changes (no pushes for
 * reading, archiving or sending), and each sync still reads every message added since the stored
 * history ID, whatever its labels (D-59), so mail filtered out of the Inbox is picked up by the
 * next sync of any kind.
 */
class HttpGmailPushApi(
    private val baseUrl: String = "https://gmail.googleapis.com",
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
) : GmailPushApi {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun watch(accessToken: String, topic: String): WatchRegistration {
        val body = json.encodeToString(WatchRequest.serializer(), WatchRequest(topicName = topic))
        val response = send(post("/gmail/v1/users/me/watch", accessToken, HttpRequest.BodyPublishers.ofString(body)))
        requireSuccess(response)
        return try {
            val wire = json.decodeFromString(WatchResponse.serializer(), response.body())
            WatchRegistration(wire.historyId, Instant.ofEpochMilli(wire.expiration.toLong()))
        } catch (e: SerializationException) {
            throw GmailPushException.Unavailable(e)
        } catch (e: IllegalArgumentException) { // a non-numeric history ID or expiration
            throw GmailPushException.Unavailable(e)
        }
    }

    override suspend fun stop(accessToken: String) {
        requireSuccess(send(post("/gmail/v1/users/me/stop", accessToken, HttpRequest.BodyPublishers.noBody())))
    }

    private fun post(path: String, accessToken: String, body: HttpRequest.BodyPublisher) =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .POST(body)
            .build()

    private suspend fun send(request: HttpRequest): HttpResponse<String> = try {
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
    } catch (e: IOException) { // includes HttpTimeoutException and refused connections
        throw GmailPushException.Unavailable(e)
    }

    private fun requireSuccess(response: HttpResponse<String>) {
        when (val status = response.statusCode()) {
            in 200..299 -> Unit
            401 -> throw GmailPushException.Unauthorized()
            else -> throw GmailPushException.Rejected(status)
        }
    }

    @Serializable
    private data class WatchRequest(
        val topicName: String,
        val labelIds: List<String> = listOf("INBOX"),
        val labelFilterBehavior: String = "include"
    )

    /** Gmail sends both as strings: a history ID and epoch milliseconds. */
    @Serializable
    private data class WatchResponse(val historyId: String, val expiration: String)
}
