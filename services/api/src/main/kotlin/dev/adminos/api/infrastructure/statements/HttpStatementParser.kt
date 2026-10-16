package dev.adminos.api.infrastructure.statements

import dev.adminos.api.domain.ingestion.statements.ParsedStatement
import dev.adminos.api.domain.ingestion.statements.StatementParseException
import dev.adminos.api.domain.ingestion.statements.StatementParser
import dev.adminos.api.domain.ingestion.statements.StatementRow
import kotlinx.coroutines.future.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * Calls the statement parser service over the internal network (D-37). The filename sent is generic,
 * keeping only the extension, so the user's real file name never leaves the API.
 */
class HttpStatementParser(
    private val baseUrl: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
) : StatementParser {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun parse(file: ByteArray, filename: String, password: String?): ParsedStatement {
        val boundary = "adminos-" + UUID.randomUUID()
        val body = multipart(boundary, file, "statement." + filename.substringAfterLast('.', "bin").lowercase().take(5), password)
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/v1/statements:parse"))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val response = try {
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: Exception) {
            throw StatementParseException("PARSER_UNAVAILABLE", "The statement parser isn't reachable", clientError = false)
        }
        return when (response.statusCode()) {
            200 -> json.decodeFromString(WireResult.serializer(), response.body()).toDomain()
            413, 422 -> {
                val err = runCatching { json.decodeFromString(WireError.serializer(), response.body()).error }.getOrNull()
                throw StatementParseException(err?.code ?: "UNREADABLE", err?.message ?: "The statement couldn't be read", clientError = true)
            }
            else -> throw StatementParseException("PARSER_ERROR", "The statement parser failed (${response.statusCode()})", clientError = false)
        }
    }

    private fun multipart(boundary: String, file: ByteArray, name: String, password: String?): ByteArray {
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray())
        w("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: application/octet-stream\r\n\r\n")
        out.write(file)
        w("\r\n")
        if (!password.isNullOrEmpty()) {
            w("--$boundary\r\nContent-Disposition: form-data; name=\"password\"\r\n\r\n$password\r\n")
        }
        w("--$boundary--\r\n")
        return out.toByteArray()
    }

    @Serializable private data class WireRow(val date: String, val description: String, val debit: String? = null, val credit: String? = null, val balance: String? = null, val reference: String? = null)
    @Serializable private data class WireResult(val bankCode: String? = null, val accountLast4: String? = null, val periodStart: String? = null, val periodEnd: String? = null, val rows: List<WireRow>, val skippedRows: Int = 0, val warnings: List<String> = emptyList())
    @Serializable private data class WireErrorBody(val code: String, val message: String)
    @Serializable private data class WireError(val error: WireErrorBody)

    private fun WireResult.toDomain() = ParsedStatement(
        bankCode = bankCode, accountLast4 = accountLast4,
        periodStart = periodStart?.let(LocalDate::parse), periodEnd = periodEnd?.let(LocalDate::parse),
        rows = rows.map { StatementRow(LocalDate.parse(it.date), it.description, it.debit?.let(::BigDecimal), it.credit?.let(::BigDecimal), it.balance?.let(::BigDecimal), it.reference) },
        skippedRows = skippedRows, warnings = warnings
    )
}
