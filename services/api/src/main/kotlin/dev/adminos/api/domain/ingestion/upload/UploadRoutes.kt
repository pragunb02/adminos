package dev.adminos.api.domain.ingestion.upload

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.ingestion.connection.ConnectionService
import dev.adminos.api.domain.ingestion.connection.SourceType
import dev.adminos.api.domain.ingestion.sync.IngestionService
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger = LoggerFactory.getLogger("UploadRoutes")
private const val MAX_PDF_SIZE = 20 * 1024 * 1024L

fun Route.uploadRoutes(ingestionService: IngestionService, connectionService: ConnectionService) {

    authenticate("auth-jwt") {
        route("/upload") {
            post("/pdf") {
                val principal = call.userPrincipal

                val multipart = call.receiveMultipart()
                var fileBytes: ByteArray? = null
                var fileName: String? = null
                var contentType: String? = null

                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            fileName = part.originalFileName
                            contentType = part.contentType?.toString()
                            fileBytes = part.streamProvider().readBytes()
                        }
                        else -> {}
                    }
                    part.dispose()
                }

                if (fileBytes == null || fileName == null) {
                    throw ApiException(HttpStatusCode.BadRequest, ApiError("UPLOAD_001", "No file provided"))
                }

                val isPdf = contentType == "application/pdf" || fileName!!.lowercase().endsWith(".pdf")
                val hasPdfMagicBytes = fileBytes!!.size >= 5 && String(fileBytes!!.take(5).toByteArray()) == "%PDF-"
                if (!isPdf || !hasPdfMagicBytes) {
                    throw ApiException(HttpStatusCode.BadRequest, ApiError("UPLOAD_002", "Only PDF files are accepted"))
                }

                if (fileBytes!!.size > MAX_PDF_SIZE) {
                    throw ApiException(HttpStatusCode.BadRequest, ApiError("UPLOAD_003", "File size exceeds 20MB limit"))
                }

                val storageKey = "uploads/${principal.userId}/${UUID.randomUUID()}/$fileName"
                logger.info("PDF upload: user={}, file={}, size={}, key={}", principal.userId, fileName, fileBytes!!.size, storageKey)

                val connection = connectionService.getOrCreateConnection(principal.userId, SourceType.PDF)

                val session = ingestionService.enqueuePdfParse(
                    userId = principal.userId,
                    connectionId = connection.id,
                    storageKey = storageKey,
                    fileName = fileName!!
                )

                call.respond(
                    HttpStatusCode.Accepted,
                    ApiResponse.success(
                        PdfUploadResponse(syncSessionId = session.id.toString(), status = session.status.name.lowercase(), storageKey = storageKey, fileName = fileName!!),
                        call.requestId
                    )
                )
            }
        }
    }
}

@Serializable
data class PdfUploadResponse(val syncSessionId: String, val status: String, val storageKey: String, val fileName: String)
