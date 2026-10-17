package dev.adminos.api.domain.ingestion.statements

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

private const val MAX_BYTES = 20L * 1024 * 1024

@Serializable
data class StatementImportResponse(
    val bankCode: String?,
    val accountLast4: String?,
    val periodStart: String?,
    val periodEnd: String?,
    val rows: Int,
    val accepted: Int,
    val duplicates: Int,
    val skippedRows: Int,
    val warnings: List<String>
)

/**
 * POST /api/v1/statements (multipart: `file`, optional `password`): import a bank statement (D-37).
 * The file and password stay in memory. Nothing about them (name, content, password) is logged or stored.
 */
fun Route.statementRoutes(service: StatementImportService) {
    authenticate("auth-jwt") {
        post("/statements") {
            val userId = call.userPrincipal.userId
            var file: ByteArray? = null
            var filename = ""
            var password: String? = null
            val form = dev.adminos.api.infrastructure.plugins.UploadForm("file", setOf("password"), maxTextLength = 256)
            call.receiveMultipart(formFieldLimit = MAX_BYTES + 1).forEachPart { part ->
                try {
                    form.check(part)
                    when (part) {
                        is PartData.FileItem -> {
                            filename = part.originalFileName ?: ""
                            file = part.provider().readRemaining(MAX_BYTES + 1).readByteArray()
                        }
                        is PartData.FormItem -> password = part.value.takeIf { it.isNotEmpty() }
                        else -> {}
                    }
                } finally {
                    part.dispose()
                }
            }
            val bytes = file ?: throw ApiException(HttpStatusCode.BadRequest, ApiError("STATEMENT_001", "Attach a statement file as 'file'"))
            if (bytes.size > MAX_BYTES) throw ApiException(HttpStatusCode.PayloadTooLarge, ApiError("FILE_TOO_LARGE", "Statement exceeds 20 MB"))

            val result = try {
                service.import(userId, bytes, filename, password)
            } catch (e: StatementParseException) {
                val status = if (e.clientError) HttpStatusCode.UnprocessableEntity else HttpStatusCode.ServiceUnavailable
                throw ApiException(status, ApiError(e.code, e.message))
            }
            call.respond(HttpStatusCode.OK, ApiResponse.success(StatementImportResponse(
                result.bankCode, result.accountLast4, result.periodStart?.toString(), result.periodEnd?.toString(),
                result.rows, result.accepted, result.duplicates, result.skippedRows, result.warnings
            ), call.requestId))
        }
    }
}
