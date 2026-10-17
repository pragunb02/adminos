package dev.adminos.api.domain.documents

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
import org.koin.ktor.ext.inject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

@Serializable
data class DocumentResponse(
    val id: String, val title: String, val category: String, val fileType: String, val mime: String, val sizeBytes: Long,
    val originalName: String?, val expiresOn: String?, val notes: String?, val createdAt: String
)

@Serializable
data class DocumentPatchRequest(
    val title: String? = null, val category: String? = null,
    /** "YYYY-MM-DD", or "" to remove the expiry. */
    val expiresOn: String? = null,
    /** Text, or "" to remove the notes. */
    val notes: String? = null
)

@Serializable
data class DocumentDeletedResponse(val deleted: Boolean)

/** Never the storage key, the wrapped key or the checksum. */
private fun Document.toResponse() = DocumentResponse(
    id.toString(), title, category.name.lowercase(), fileType.name.lowercase(), fileType.mime, sizeBytes,
    originalName, expiresOn?.toString(), notes, createdAt.toString()
)

/**
 * The documents vault (AOS-507), signed-in users only, each sees only their own:
 * - `POST /documents` multipart: `file` (PDF/PNG/JPEG/WebP/HEIC, ≤ 20 MB), `category`, optional `title`, `expiresOn`, `notes`
 * - `GET /documents?category=&q=` · `GET /documents/{id}` · `GET /documents/{id}/file` (decrypted, as an attachment)
 * - `PATCH /documents/{id}` · `DELETE /documents/{id}`
 */
fun Route.documentRoutes(service: DocumentService) {
    authenticate("auth-jwt") {
        route("/documents") {
            post {
                var bytes: ByteArray? = null
                var name: String? = null
                val fields = mutableMapOf<String, String>()
                call.receiveMultipart(formFieldLimit = Document.MAX_BYTES + 1).forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> if (part.name == "file") {
                            name = part.originalFileName
                            bytes = part.provider().readRemaining(Document.MAX_BYTES + 1).readByteArray()
                        }
                        is PartData.FormItem -> part.name?.let { fields[it] = part.value }
                        else -> {}
                    }
                    part.dispose()
                }
                val file = bytes ?: throw ApiException(HttpStatusCode.BadRequest, ApiError("DOC_006", "Attach the file as 'file'"))
                val upload = DocumentUpload(
                    bytes = file, originalName = name, title = fields["title"],
                    category = category(fields["category"]) ?: throw IllegalArgumentException("category: one of ${categories()}"),
                    expiresOn = fields["expiresOn"]?.takeIf { it.isNotBlank() }?.let(::date),
                    notes = fields["notes"]?.also { require(it.length <= Document.MAX_NOTES) { "notes: at most ${Document.MAX_NOTES} characters" } }
                )
                val saved = mapped { service.upload(call.userPrincipal.userId, upload) }
                call.respond(HttpStatusCode.Created, ApiResponse.success(saved.toResponse(), call.requestId))
            }
            get {
                val q = call.request.queryParameters
                val category = q["category"]?.let { category(it) ?: throw IllegalArgumentException("category: one of ${categories()}") }
                val list = service.list(call.userPrincipal.userId, category, q["q"])
                call.respond(HttpStatusCode.OK, ApiResponse.success(list.map { it.toResponse() }, call.requestId))
            }
            get("/{id}") {
                val d = mapped { service.get(call.userPrincipal.userId, id(call.parameters["id"])) }
                call.respond(HttpStatusCode.OK, ApiResponse.success(d.toResponse(), call.requestId))
            }
            get("/{id}/file") {
                val file = mapped { service.download(call.userPrincipal.userId, id(call.parameters["id"])) }
                call.response.header(HttpHeaders.ContentDisposition,
                    "attachment; filename=\"${file.downloadName.replace(Regex("[^A-Za-z0-9._ -]"), "_")}\"; filename*=UTF-8''${URLEncoder.encode(file.downloadName, Charsets.UTF_8).replace("+", "%20")}")
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.response.header("X-Content-Type-Options", "nosniff")
                call.respondBytes(file.bytes, ContentType.parse(file.type.mime))
            }
            patch("/{id}") {
                val r = call.receive<DocumentPatchRequest>()
                val patch = DocumentPatch(
                    title = r.title?.also { require(it.length <= Document.MAX_TITLE) { "title: at most ${Document.MAX_TITLE} characters" } },
                    category = r.category?.let { category(it) ?: throw IllegalArgumentException("category: one of ${categories()}") },
                    expiresOn = r.expiresOn?.takeIf { it.isNotEmpty() }?.let(::date), clearExpiry = r.expiresOn == "",
                    notes = r.notes?.takeIf { it.isNotEmpty() }?.also { require(it.length <= Document.MAX_NOTES) { "notes: at most ${Document.MAX_NOTES} characters" } },
                    clearNotes = r.notes == ""
                )
                val d = mapped { service.update(call.userPrincipal.userId, id(call.parameters["id"]), patch) }
                call.respond(HttpStatusCode.OK, ApiResponse.success(d.toResponse(), call.requestId))
            }
            delete("/{id}") {
                mapped { service.delete(call.userPrincipal.userId, id(call.parameters["id"])) }
                call.respond(HttpStatusCode.OK, ApiResponse.success(DocumentDeletedResponse(true), call.requestId))
            }
        }
    }
}

private fun categories() = DocumentCategory.entries.joinToString { it.name.lowercase() }
private fun category(raw: String?) = DocumentCategory.entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }

private fun id(raw: String?): UUID = try { UUID.fromString(raw) } catch (e: IllegalArgumentException) { throw ApiException(HttpStatusCode.NotFound, NOT_FOUND) }

private fun date(raw: String): LocalDate = try { LocalDate.parse(raw.trim()) } catch (e: DateTimeParseException) {
    throw IllegalArgumentException("expiresOn: expected YYYY-MM-DD")
}

private val NOT_FOUND = ApiError("DOC_001", "Document not found")

/** Domain errors → HTTP: a clear status and code for each, never a 500 for expected cases. */
private suspend fun <T> mapped(work: suspend () -> T): T = try {
    work()
} catch (e: DocumentNotFoundException) {
    throw ApiException(HttpStatusCode.NotFound, NOT_FOUND)
} catch (e: UnsupportedDocumentTypeException) {
    throw ApiException(HttpStatusCode.UnsupportedMediaType, ApiError("DOC_002", e.message.orEmpty()))
} catch (e: DocumentTooLargeException) {
    throw ApiException(HttpStatusCode.PayloadTooLarge, ApiError("FILE_TOO_LARGE", e.message.orEmpty()))
} catch (e: EmptyDocumentException) {
    throw ApiException(HttpStatusCode.BadRequest, ApiError("DOC_007", e.message.orEmpty()))
} catch (e: DocumentStorageNotConfiguredException) {
    throw ApiException(HttpStatusCode.ServiceUnavailable, ApiError("DOC_005", "The documents vault isn't set up on this server yet"))
} catch (e: DocumentObjectMissingException) {
    throw ApiException(HttpStatusCode.BadGateway, ApiError("DOC_004", "The stored file is missing"))
} catch (e: DocumentStorageException) {
    throw ApiException(HttpStatusCode.ServiceUnavailable, ApiError("DOC_003", "Document storage is unavailable; try again shortly"))
} catch (e: DocumentIntegrityException) {
    throw ApiException(HttpStatusCode.BadGateway, ApiError("DOC_004", "The stored file failed its integrity check"))
}

/** Registers the documents vault routes under /api/v1 (AOS-507). */
fun io.ktor.server.application.Application.documentsModule() {
    val service: DocumentService by inject()
    routing { route("/api/v1") { documentRoutes(service) } }
}
