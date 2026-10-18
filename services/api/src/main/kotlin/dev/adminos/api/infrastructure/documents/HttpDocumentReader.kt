package dev.adminos.api.infrastructure.documents

import dev.adminos.api.domain.documents.DocumentFileType
import dev.adminos.api.domain.documents.reading.DocumentField
import dev.adminos.api.domain.documents.reading.DocumentReadException
import dev.adminos.api.domain.documents.reading.DocumentReader
import dev.adminos.api.domain.documents.reading.DocumentText
import dev.adminos.api.domain.documents.reading.ReadProposal
import dev.adminos.api.domain.documents.reading.TextSource
import kotlinx.coroutines.future.await
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * Calls the parser service's document reader over the internal network (AOS-717; the parser's network has no
 * internet, D-91). The decrypted bytes go in the request body only; the file name sent is generic. The answer is
 * size-checked and validated: a parser that misbehaves can't push oversized text or unknown fields into the API.
 */
class HttpDocumentReader(
    private val baseUrl: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    private val timeout: Duration = Duration.ofSeconds(90)
) : DocumentReader {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun read(bytes: ByteArray, type: DocumentFileType): DocumentText {
        val boundary = "adminos-" + UUID.randomUUID()
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/v1/documents:read"))
            .timeout(timeout)
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, bytes, "document.${type.extension}")))
            .build()
        val response = try {
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw DocumentReadException("READER_UNAVAILABLE", "The document reader isn't reachable", clientError = false)
        }
        val body = response.body()
        if (body.length > MAX_BODY_CHARS) throw DocumentReadException("READER_ERROR", "The document reader answered too much", clientError = false)
        return when (response.statusCode()) {
            200 -> try {
                json.decodeFromString(Wire.serializer(), body).toDomain()
            } catch (_: SerializationException) {
                throw DocumentReadException("READER_ERROR", "The document reader's answer wasn't understood", clientError = false)
            } catch (_: IllegalArgumentException) {
                throw DocumentReadException("READER_ERROR", "The document reader's answer wasn't understood", clientError = false)
            }
            413, 422 -> {
                val code = runCatching { json.decodeFromString(WireError.serializer(), body).error.code }.getOrNull()?.takeIf { CODE.matches(it) } ?: "UNREADABLE"
                // OCR missing on the server is the server's problem, not the file's.
                throw DocumentReadException(code, MESSAGES[code] ?: "The document couldn't be read", clientError = code != "OCR_UNAVAILABLE")
            }
            else -> throw DocumentReadException("READER_ERROR", "The document reader failed (${response.statusCode()})", clientError = false)
        }
    }

    private fun multipart(boundary: String, file: ByteArray, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
        out.write(file)
        out.write("\r\n--$boundary--\r\n".toByteArray())
        return out.toByteArray()
    }

    @Serializable private data class WireProposal(val field: String, val value: String, val evidence: String, val label: String = "", val rule: String = "")
    @Serializable private data class Wire(val source: String, val pagesTotal: Int, val pagesRead: Int, val truncated: Boolean, val text: String, val proposals: List<WireProposal>)
    @Serializable private data class WireErrorBody(val code: String)
    @Serializable private data class WireError(val error: WireErrorBody)

    private fun Wire.toDomain(): DocumentText {
        require(text.length <= MAX_TEXT_CHARS && proposals.size <= MAX_PROPOSALS && pagesRead in 0..pagesTotal) { "out of bounds" }
        return DocumentText(
            text = text,
            source = TextSource.valueOf(source.uppercase()),
            pagesRead = pagesRead, pagesTotal = pagesTotal, truncated = truncated,
            proposals = proposals.mapNotNull { p ->
                DocumentField.fromWire(p.field)?.let { ReadProposal(it, p.value.take(MAX_VALUE), p.evidence.take(MAX_VALUE), p.label.take(40), p.rule.take(40)) }
            }
        )
    }

    companion object {
        const val MAX_TEXT_CHARS = 20_000
        const val MAX_BODY_CHARS = 64_000
        const val MAX_PROPOSALS = 20
        const val MAX_VALUE = 200
        private val CODE = Regex("[A-Z_]{3,32}")

        /** Our own words for the reader's codes: its messages are never passed through. */
        private val MESSAGES = mapOf(
            "UNSUPPORTED_IMAGE" to "HEIC photos can't be read yet. Save the photo as JPEG and upload that",
            "UNSUPPORTED_FORMAT" to "This file can't be read",
            "IMAGE_TOO_LARGE" to "The image is too large to read",
            "PASSWORD_PROTECTED" to "This PDF is password-protected",
            "TOO_MANY_PAGES" to "The document has too many pages to read",
            "FILE_TOO_LARGE" to "The document is too large to read",
            "READ_TIMEOUT" to "The document took too long to read",
            "OCR_TIMEOUT" to "The document took too long to read",
            "OCR_UNAVAILABLE" to "Reading photos and scans isn't set up on this server",
            "EMPTY_FILE" to "The file is empty"
        )
    }
}
