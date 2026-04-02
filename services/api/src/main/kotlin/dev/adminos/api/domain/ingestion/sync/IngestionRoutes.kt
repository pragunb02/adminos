package dev.adminos.api.domain.ingestion.sync

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.ingestion.SmsBatchRequest
import dev.adminos.api.domain.ingestion.SmsBatchResponse
import dev.adminos.api.domain.ingestion.SyncSessionResponse
import dev.adminos.api.domain.ingestion.connection.ConnectionService
import dev.adminos.api.domain.ingestion.connection.SourceType
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Route.ingestionRoutes(ingestionService: IngestionService, connectionService: ConnectionService) {

    authenticate("auth-jwt") {

        route("/ingest") {
            post("/sms") {
                val principal = call.userPrincipal
                val request = call.receive<SmsBatchRequest>()

                val hasRawText = request.batch.any { it.rawText != null }
                if (hasRawText) {
                    throw ApiException(HttpStatusCode.BadRequest, ApiError.RAW_TEXT_REJECTED)
                }

                if (request.batch.size > 100) {
                    throw ApiException(HttpStatusCode.BadRequest, ApiError.BATCH_TOO_LARGE)
                }

                if (request.batch.isEmpty()) {
                    throw ApiException(HttpStatusCode.BadRequest, ApiError("INGEST_004", "Batch must not be empty"))
                }

                val connection = connectionService.getOrCreateConnection(principal.userId, SourceType.SMS)

                val session = ingestionService.ingestSmsBatch(
                    userId = principal.userId,
                    connectionId = connection.id,
                    records = request.batch
                )

                call.respond(
                    HttpStatusCode.Accepted,
                    ApiResponse.success(
                        SmsBatchResponse(syncSessionId = session.id.toString(), status = session.status.name.lowercase(), totalItems = session.totalItems),
                        call.requestId
                    )
                )
            }
        }

        route("/sync") {
            get("/{id}") {
                val principal = call.userPrincipal
                val sessionId = call.parameters["id"]
                    ?: throw ApiException(HttpStatusCode.BadRequest, ApiError("SYNC_002", "Missing session ID"))

                val session = ingestionService.getSyncSession(UUID.fromString(sessionId))
                    ?: throw ApiException(HttpStatusCode.NotFound, ApiError("SYNC_003", "Sync session not found"))

                if (session.userId != principal.userId) {
                    throw ApiException(HttpStatusCode.NotFound, ApiError("SYNC_003", "Sync session not found"))
                }

                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(
                        SyncSessionResponse(
                            id = session.id.toString(),
                            syncType = session.syncType.name.lowercase(),
                            status = session.status.name.lowercase(),
                            totalItems = session.totalItems,
                            processedItems = session.processedItems,
                            failedItems = session.failedItems,
                            duplicateItems = session.duplicateItems,
                            netNewItems = session.netNewItems,
                            startedAt = session.startedAt?.toString(),
                            completedAt = session.completedAt?.toString()
                        ),
                        call.requestId
                    )
                )
            }
        }
    }
}
