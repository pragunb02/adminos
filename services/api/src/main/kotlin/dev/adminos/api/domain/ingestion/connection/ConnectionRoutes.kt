package dev.adminos.api.domain.ingestion.connection

import dev.adminos.api.domain.audit.ActorType
import dev.adminos.api.domain.audit.AuditActions
import dev.adminos.api.domain.audit.AuditService
import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.identity.GoogleOAuthClient
import dev.adminos.api.domain.ingestion.SmsBatchResponse
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.RateLimitPlugin
import dev.adminos.api.infrastructure.plugins.RateLimiters
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Route.connectionRoutes(connectionService: ConnectionService, auditService: AuditService, googleOAuthClient: GoogleOAuthClient? = null) {

    authenticate("auth-jwt") {

        route("/connections") {

            get {
                val principal = call.userPrincipal
                val connections = connectionService.getUserConnections(principal.userId)
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(connections.map { ConnectionResponse.from(it) }, call.requestId)
                )
            }

            post {
                val principal = call.userPrincipal
                val request = call.receive<CreateConnectionRequest>()
                val ipAddress = call.request.local.remoteHost

                val sourceType = try {
                    SourceType.valueOf(request.sourceType.uppercase())
                } catch (e: IllegalArgumentException) {
                    throw ApiException(HttpStatusCode.BadRequest, ApiError("CONN_003", "Invalid source type: ${request.sourceType}"))
                }

                val connection = if (sourceType == SourceType.GMAIL && request.code != null && googleOAuthClient != null) {
                    // Exchange OAuth code for tokens and store them
                    val redirectUri = request.redirectUri ?: throw ApiException(
                        HttpStatusCode.BadRequest, ApiError("CONN_005", "redirectUri is required for OAuth code exchange")
                    )
                    val (tokens, userInfo) = googleOAuthClient.exchangeCodeForTokens(request.code, redirectUri)
                    connectionService.createConnection(
                        userId = principal.userId,
                        sourceType = sourceType,
                        gmailAddress = userInfo.email,
                        accessToken = tokens.accessToken,
                        refreshToken = tokens.refreshToken
                    )
                } else {
                    connectionService.createConnection(
                        userId = principal.userId,
                        sourceType = sourceType,
                        gmailAddress = if (sourceType == SourceType.GMAIL) "user@gmail.com" else null
                    )
                }

                auditService.log(
                    userId = principal.userId,
                    actor = ActorType.USER,
                    action = AuditActions.CONNECTION_CREATE,
                    entityType = "connection",
                    entityId = connection.id,
                    ipAddress = ipAddress
                )

                call.respond(HttpStatusCode.Created, ApiResponse.success(ConnectionResponse.from(connection), call.requestId))
            }

            delete("/{id}") {
                val principal = call.userPrincipal
                val ipAddress = call.request.local.remoteHost
                val connectionId = call.parameters["id"]
                    ?: throw ApiException(HttpStatusCode.BadRequest, ApiError.CONNECTION_NOT_FOUND)

                try {
                    val connection = connectionService.disconnect(principal.userId, UUID.fromString(connectionId))
                    auditService.log(
                        userId = principal.userId,
                        actor = ActorType.USER,
                        action = AuditActions.CONNECTION_DISCONNECT,
                        entityType = "connection",
                        entityId = UUID.fromString(connectionId),
                        ipAddress = ipAddress
                    )
                    call.respond(HttpStatusCode.OK, ApiResponse.success(ConnectionResponse.from(connection), call.requestId))
                } catch (e: ConnectionNotFoundException) {
                    throw ApiException(HttpStatusCode.NotFound, ApiError.CONNECTION_NOT_FOUND)
                }
            }

            post("/{id}/sync") {
                val principal = call.userPrincipal
                val connectionId = call.parameters["id"]
                    ?: throw ApiException(HttpStatusCode.BadRequest, ApiError.CONNECTION_NOT_FOUND)

                val rateLimiter = RateLimiters.manualSync
                if (!rateLimiter.isAllowed("sync:${principal.userId}")) {
                    val retryAfter = rateLimiter.retryAfterSeconds("sync:${principal.userId}")
                    throw ApiException(HttpStatusCode.TooManyRequests, ApiError("RATE_001", "Manual sync rate limit exceeded. Try again in $retryAfter seconds."))
                }

                try {
                    val session = connectionService.triggerManualSync(principal.userId, UUID.fromString(connectionId))
                    call.respond(HttpStatusCode.Accepted, ApiResponse.success(
                        SmsBatchResponse(syncSessionId = session.id.toString(), status = session.status.name.lowercase(), totalItems = 0),
                        call.requestId
                    ))
                } catch (e: ConnectionNotFoundException) {
                    throw ApiException(HttpStatusCode.NotFound, ApiError.CONNECTION_NOT_FOUND)
                } catch (e: ConnectionNotConnectedException) {
                    throw ApiException(HttpStatusCode.BadRequest, ApiError("CONN_004", "Connection is not active"))
                }
            }
        }
    }
}
