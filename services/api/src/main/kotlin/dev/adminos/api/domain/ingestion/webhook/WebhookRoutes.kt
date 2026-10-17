package dev.adminos.api.domain.ingestion.webhook

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.ingestion.connection.ConnectionService
import dev.adminos.api.domain.ingestion.sync.IngestionService
import dev.adminos.api.infrastructure.auth.OidcVerifier
import dev.adminos.api.infrastructure.auth.PushTokenVerifier
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.requestId
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.Base64

private val logger = LoggerFactory.getLogger("WebhookRoutes")

/** Production wiring: a Google OIDC verifier, or none (the endpoint then refuses everything) when not configured. */
fun Route.webhookRoutes(
    connectionService: ConnectionService,
    ingestionService: IngestionService,
    pubsubAudience: String,
    pubsubServiceAccount: String
) = webhookRoutes(connectionService, ingestionService, OidcVerifier(pubsubAudience, pubsubServiceAccount).takeIf { it.isConfigured() })

/**
 * POST /api/v1/webhooks/gmail — Gmail push via Pub/Sub. Fails closed (AOS-609): with no [verifier]
 * configured it answers 503 and accepts nothing; every call must carry a verified push token.
 * What a push does is [GmailPushService]'s: at most one waiting sync per connection (AOS-630).
 */
fun Route.webhookRoutes(
    connectionService: ConnectionService,
    ingestionService: IngestionService,
    verifier: PushTokenVerifier?,
    pushes: GmailPushService = GmailPushService(connectionService, ingestionService)
) {
    route("/webhooks") {
        post("/gmail") {
            if (verifier == null) {
                logger.warn("Gmail webhook called but GOOGLE_PUBSUB_AUDIENCE / GOOGLE_PUBSUB_SERVICE_ACCOUNT are not set: refusing")
                throw ApiException(HttpStatusCode.ServiceUnavailable, ApiError("WEBHOOK_007", "Gmail push is not configured"))
            }
            val authHeader = call.request.header(HttpHeaders.Authorization)
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                logger.warn("Gmail webhook: missing or invalid Authorization header")
                throw ApiException(HttpStatusCode.Unauthorized, ApiError("WEBHOOK_005", "Missing Pub/Sub authorization token"))
            }

            val bearerToken = authHeader.removePrefix("Bearer ")

            try {
                verifier.verify(bearerToken)
            } catch (e: Exception) {
                logger.warn("Gmail webhook: OIDC verification failed: ${e.message}")
                throw ApiException(HttpStatusCode.Unauthorized, ApiError("WEBHOOK_006", "Invalid Pub/Sub OIDC token"))
            }

            val body = call.receiveText()

            val pushMessage = try {
                Json.decodeFromString<PubSubPushMessage>(body)
            } catch (e: Exception) {
                throw ApiException(HttpStatusCode.BadRequest, ApiError("WEBHOOK_001", "Invalid Pub/Sub message format"))
            }

            val decodedData = try {
                String(Base64.getDecoder().decode(pushMessage.message.data))
            } catch (e: Exception) {
                throw ApiException(HttpStatusCode.BadRequest, ApiError("WEBHOOK_002", "Failed to decode notification data"))
            }

            val notification = try {
                Json.decodeFromString<GmailNotification>(decodedData)
            } catch (e: Exception) {
                throw ApiException(HttpStatusCode.BadRequest, ApiError("WEBHOOK_003", "Invalid Gmail notification format"))
            }

            if (notification.emailAddress.isBlank()) {
                throw ApiException(HttpStatusCode.BadRequest, ApiError("WEBHOOK_004", "Missing email address in notification"))
            }

            // Every outcome is a 200: Pub/Sub retries anything else, which would only add pushes.
            val ack = when (val outcome = pushes.onPush(notification.emailAddress, notification.historyId)) {
                GmailPushOutcome.NoConnection -> WebhookAckResponse(acknowledged = true, message = "No matching connection")
                GmailPushOutcome.NotActive -> WebhookAckResponse(acknowledged = true, message = "Connection not active")
                GmailPushOutcome.AlreadySynced -> WebhookAckResponse(acknowledged = true, message = "Already synced")
                is GmailPushOutcome.Coalesced ->
                    WebhookAckResponse(acknowledged = true, message = "Sync already queued", syncSessionId = outcome.pendingSessionId.toString())
                is GmailPushOutcome.Queued -> WebhookAckResponse(acknowledged = true, syncSessionId = outcome.session.id.toString())
            }
            call.respond(HttpStatusCode.OK, ApiResponse.success(ack, call.requestId))
        }
    }
}

@Serializable
data class PubSubPushMessage(val message: PubSubMessage, val subscription: String? = null)

@Serializable
data class PubSubMessage(val data: String, val messageId: String? = null, val publishTime: String? = null)

@Serializable
data class GmailNotification(val emailAddress: String, val historyId: Long? = null)

@Serializable
data class WebhookAckResponse(val acknowledged: Boolean, val message: String? = null, val syncSessionId: String? = null)
