package dev.adminos.api.domain.wealth.imports

import dev.adminos.api.domain.common.ApiError
import dev.adminos.api.domain.common.ApiResponse
import dev.adminos.api.domain.wealth.ImportBatch
import dev.adminos.api.infrastructure.plugins.ApiException
import dev.adminos.api.infrastructure.plugins.UploadForm
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
import java.util.UUID

private const val MAX_BYTES = 20L * 1024 * 1024

@Serializable data class ImportBatchDto(
    val id: String, val reader: String, val statementFrom: String?, val statementTo: String?, val status: String,
    val added: Int, val duplicates: Int, val createdAt: String, val undoneAt: String?
)
@Serializable data class HoldingStatusDto(
    val folio: String, val amc: String, val scheme: String, val status: String, val reason: String?,
    val statementDate: String, val statementUnits: String, val ledgerUnits: String
)
@Serializable data class CasImportResponse(val batch: ImportBatchDto, val holdings: List<HoldingStatusDto>, val blocked: Int)
@Serializable data class ImportsResponse(val imports: List<ImportBatchDto>)
@Serializable data class ReconciliationResponse(val holdings: List<HoldingStatusDto>, val blocked: Int)

val IMPORT_NOT_FOUND = ApiError("WEALTH_001", "Import not found")

internal fun ImportBatch.dto() = ImportBatchDto(
    id.toString(), readerId, statementFrom?.toString(), statementTo?.toString(), status.wire, added, duplicates, createdAt.toString(), undoneAt?.toString()
)

internal fun ImportedHolding.dto() = HoldingStatusDto(
    folio = folioMasked, amc = amc, scheme = status.check.schemeName, status = status.status.wire, reason = status.reason,
    statementDate = status.check.statementTo.toString(), statementUnits = status.check.closingUnits.toString(), ledgerUnits = status.ledgerUnits.toString()
)

/**
 * Mutual fund imports (AOS-805, D-79):
 * - `POST /wealth/imports/cas` (multipart `file`, `password`): import a CAMS/KFintech CAS → 201 with each holding's
 *   reconciliation; 422 with the parser's code when it can't be read. The file and password are never stored or logged.
 * - `GET /wealth/imports`: the imports, newest first;
 * - `POST /wealth/imports/{id}/undo`: undo one (404 for anyone else's or an undone one);
 * - `GET /wealth/reconciliation`: every holding's status now, blocked first (Review).
 */
fun Route.wealthImportRoutes(service: CasImportService) {
    authenticate("auth-jwt") {
        route("/wealth") {
            post("/imports/cas") {
                val userId = call.userPrincipal.userId
                var file: ByteArray? = null
                var password: String? = null
                val form = UploadForm("file", setOf("password"), maxTextLength = 256)
                call.receiveMultipart(formFieldLimit = MAX_BYTES + 1).forEachPart { part ->
                    try {
                        form.check(part)
                        when (part) {
                            is PartData.FileItem -> file = part.provider().readRemaining(MAX_BYTES + 1).readByteArray()
                            is PartData.FormItem -> password = part.value.takeIf { it.isNotEmpty() }
                            else -> {}
                        }
                    } finally {
                        part.dispose()
                    }
                }
                val bytes = file ?: throw ApiException(HttpStatusCode.BadRequest, ApiError("WEALTH_002", "Attach the statement PDF as 'file'"))
                if (bytes.size > MAX_BYTES) throw ApiException(HttpStatusCode.PayloadTooLarge, ApiError("FILE_TOO_LARGE", "Statement exceeds 20 MB"))
                val result = try {
                    service.import(userId, bytes, password)
                } catch (e: CasParseException) {
                    val status = if (e.clientError) HttpStatusCode.UnprocessableEntity else HttpStatusCode.ServiceUnavailable
                    throw ApiException(status, ApiError(e.code, e.message))
                }
                call.respond(HttpStatusCode.Created, ApiResponse.success(
                    CasImportResponse(result.batch.dto(), result.holdings.map { it.dto() }, result.blocked), call.requestId))
            }
            get("/imports") {
                val batches = service.batches(call.userPrincipal.userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(ImportsResponse(batches.map { it.dto() }), call.requestId))
            }
            post("/imports/{id}/undo") {
                val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: throw ApiException(HttpStatusCode.NotFound, IMPORT_NOT_FOUND)
                if (!service.undo(call.userPrincipal.userId, id)) throw ApiException(HttpStatusCode.NotFound, IMPORT_NOT_FOUND)
                call.respond(HttpStatusCode.OK, ApiResponse.success(mapOf("undone" to true), call.requestId))
            }
            get("/reconciliation") {
                val holdings = service.review(call.userPrincipal.userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(
                    ReconciliationResponse(holdings.map { it.dto() }, holdings.count { it.status.blocked }), call.requestId))
            }
        }
    }
}
