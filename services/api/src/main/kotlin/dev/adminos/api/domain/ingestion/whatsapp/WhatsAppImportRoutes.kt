package dev.adminos.api.domain.ingestion.whatsapp

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.requestId
import dev.adminos.api.infrastructure.plugins.userPrincipal
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable

/** The upload (a zip may carry media); the chat text inside is capped separately at 10 MB. */
private const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024

@Serializable
data class WhatsAppImportResponse(
    val messages: Int,
    val fromBanks: Int,
    val accepted: Int,
    val duplicates: Int,
    val mandates: Int,
    val ignored: Map<String, Int>,
    val unreadableLines: Int
)

/**
 * POST /api/v1/imports/whatsapp (multipart: `file`, a `.txt` or `.zip` chat export): import bank messages
 * from a WhatsApp chat (AOS-403). The file stays in memory and nothing about it is logged or stored (D-33).
 */
fun Route.whatsAppImportRoutes(service: WhatsAppImportService) {
    authenticate("auth-jwt") {
        post("/imports/whatsapp") {
            val userId = call.userPrincipal.userId
            var file: ByteArray? = null
            call.receiveMultipart(formFieldLimit = MAX_UPLOAD_BYTES + 1).forEachPart { part ->
                if (part is PartData.FileItem && part.name == "file") {
                    file = part.provider().readRemaining(MAX_UPLOAD_BYTES + 1).readByteArray()
                }
                part.dispose()
            }
            val bytes = file ?: throw ApiException(HttpStatusCode.BadRequest, ApiError("WHATSAPP_NO_FILE", "Attach the chat export as 'file'"))
            if (bytes.size > MAX_UPLOAD_BYTES) throw ApiException(HttpStatusCode.PayloadTooLarge, ApiError("FILE_TOO_LARGE", "The file is too large (at most 20 MB)"))

            val result = try {
                service.import(userId, bytes)
            } catch (e: WhatsAppImportException) {
                val status = if (e.code == "FILE_TOO_LARGE") HttpStatusCode.PayloadTooLarge else HttpStatusCode.UnprocessableEntity
                throw ApiException(status, ApiError(e.code, e.message))
            }
            call.respond(HttpStatusCode.OK, ApiResponse.success(WhatsAppImportResponse(
                result.messages, result.fromBanks, result.accepted, result.duplicates, result.mandates, result.ignored, result.unreadableLines
            ), call.requestId))
        }
    }
}
