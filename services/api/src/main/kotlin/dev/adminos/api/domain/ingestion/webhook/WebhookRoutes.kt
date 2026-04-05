package dev.adminos.api.domain.ingestion.webhook

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.ingestion.connection.ConnectionService
import dev.adminos.api.domain.ingestion.connection.ConnectionStatus
import dev.adminos.api.domain.ingestion.sync.IngestionService
import dev.adminos.api.infrastructure.auth.OidcVerifier
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

fun Route.webhookRoutes(
    connectionService: ConnectionService,
    ingestionService: IngestionService,
    pubsubAudience: String
) {
    val oidcVerifier = OidcVerifier(pubsubAudience)

    route("/webhooks") {
        post("/gmail") {
            val authHeader = call.request.header(HttpHeaders.Authorization)
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                logger.warn("Gmail webhook: missing or invalid Authorization header")
                throw ApiException(HttpStatusCode.Unauthorized, ApiError("WEBHOOK_005", "Missing Pub/Sub authorization token"))
            }

            val bearerToken = authHeader.removePrefix("Bearer ")

            if (oidcVerifier.isConfigured()) {
                try {
                    oidcVerifier.verify(bearerToken)
                } catch (e: Exception) {
                    logger.warn("Gmail webhook: OIDC verification failed: ${e.message}")
                    throw ApiException(HttpStatusCode.Unauthorized, ApiError("WEBHOOK_006", "Invalid Pub/Sub OIDC token"))
                }
            } else {
                logger.warn("Gmail webhook: GOOGLE_PUBSUB_AUDIENCE not set, skipping OIDC verification")
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

            val connection = connectionService.findByGmailAddress(notification.emailAddress)
            if (connection == null) {
                call.respond(HttpStatusCode.OK, ApiResponse.success(
                    WebhookAckResponse(acknowledged = true, message = "No matching connection"), call.requestId
                ))
                return@post
            }

            if (connection.status != ConnectionStatus.CONNECTED) {
                call.respond(HttpStatusCode.OK, ApiResponse.success(
                    WebhookAckResponse(acknowledged = true, message = "Connection not active"), call.requestId
                ))
                return@post
            }

            val session = ingestionService.enqueueGmailIngest(
                userId = connection.userId,
                connectionId = connection.id,
                historyId = notification.historyId?.toString()
            )

            call.respond(HttpStatusCode.OK, ApiResponse.success(
                WebhookAckResponse(acknowledged = true, syncSessionId = session.id.toString()), call.requestId
            ))
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
