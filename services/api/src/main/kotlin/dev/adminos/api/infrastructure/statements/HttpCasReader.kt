package dev.adminos.api.infrastructure.statements

import dev.adminos.api.domain.wealth.MfPlan
import dev.adminos.api.domain.wealth.Quantity
import dev.adminos.api.domain.wealth.UnitPrice
import dev.adminos.api.domain.wealth.imports.CasFolio
import dev.adminos.api.domain.wealth.imports.CasParseException
import dev.adminos.api.domain.wealth.imports.CasReader
import dev.adminos.api.domain.wealth.imports.CasRow
import dev.adminos.api.domain.wealth.imports.CasScheme
import dev.adminos.api.domain.wealth.imports.ParsedCas
import kotlinx.coroutines.future.await
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.Duration
import java.util.UUID

/**
 * Calls the parser service's CAS reader over the internal network (AOS-805, D-37). The file name sent is generic,
 * and the reply is validated into exact types: a reply that breaks the contract is a server error, never data.
 */
class HttpCasReader(
    private val baseUrl: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
) : CasReader {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun read(file: ByteArray, password: String?): ParsedCas {
        val boundary = "adminos-" + UUID.randomUUID()
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/v1/cas:parse"))
            .timeout(Duration.ofSeconds(90))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(parserMultipart(boundary, file, "statement.pdf", password)))
            .build()
        val response = try {
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: java.io.IOException) {
            throw CasParseException("PARSER_UNAVAILABLE", "The statement parser isn't reachable", clientError = false)
        }
        return when (response.statusCode()) {
            200 -> decode(response.body())
            413, 422 -> {
                val err = runCatching { json.decodeFromString(WireError.serializer(), response.body()).error }.getOrNull()
                throw CasParseException(err?.code?.takeIf { it.matches(CODE) } ?: "UNREADABLE", err?.message?.take(200) ?: "The statement couldn't be read", clientError = true)
            }
            else -> throw CasParseException("PARSER_ERROR", "The statement parser failed (${response.statusCode()})", clientError = false)
        }
    }

    internal fun decode(body: String): ParsedCas = try {
        json.decodeFromString(WireCas.serializer(), body).toDomain()
    } catch (e: SerializationException) {
        throw contract()
    } catch (e: IllegalArgumentException) { // a number, date or code outside the contract
        throw contract()
    } catch (e: DateTimeParseException) {
        throw contract()
    }

    private fun contract() = CasParseException("PARSER_CONTRACT", "The statement parser sent an unexpected reply", clientError = false)

    @Serializable private data class WireTxn(val date: String, val kind: String, val amount: String? = null, val units: String? = null, val nav: String? = null, val balance: String? = null)
    @Serializable private data class WireScheme(
        val name: String, val isin: String? = null, val amfiCode: String? = null, val schemeType: String? = null, val category: String? = null,
        val plan: String? = null, val openingUnits: String, val closingUnits: String, val valuationDate: String? = null, val nav: String? = null,
        val value: String? = null, val transactions: List<WireTxn>
    )
    @Serializable private data class WireFolio(val ref: String, val amc: String, val schemes: List<WireScheme>)
    @Serializable private data class WireIdentifier(val ref: String, val value: String)
    @Serializable private data class WireIdentifiers(val folios: List<WireIdentifier>)
    @Serializable private data class WireCas(
        val readerId: String, val readerVersion: String, val issuer: String, val statementFrom: String? = null, val statementTo: String? = null,
        val folios: List<WireFolio>, val identifiers: WireIdentifiers, val parseWarnings: Int = 0
    )
    @Serializable private data class WireErrorBody(val code: String, val message: String)
    @Serializable private data class WireError(val error: WireErrorBody)

    private fun WireCas.toDomain(): ParsedCas {
        require(folios.size <= MAX_FOLIOS) { "too many folios" }
        val numbers = identifiers.folios.associate { it.ref to it.value }
        return ParsedCas(
            readerId = readerId, readerVersion = readerVersion, issuer = issuer,
            statementFrom = statementFrom?.let(LocalDate::parse), statementTo = statementTo?.let(LocalDate::parse),
            folios = folios.map { f ->
                CasFolio(folioNumber = requireNotNull(numbers[f.ref]) { "folio without its identifier" }, amc = f.amc.take(120), schemes = f.schemes.map { it.toDomain() })
            },
            parseWarnings = parseWarnings
        )
    }

    private fun WireScheme.toDomain() = CasScheme(
        name = name.take(255), isin = isin, amfiCode = amfiCode, schemeType = schemeType?.take(20), category = category?.take(80),
        plan = plan?.let { p -> requireNotNull(MfPlan.entries.firstOrNull { it.wire == p }) { "unknown plan" } },
        openingUnits = Quantity.parse(openingUnits), closingUnits = Quantity.parse(closingUnits),
        valuationDate = valuationDate?.let(LocalDate::parse), nav = nav?.let(UnitPrice::parse), value = value?.let(::money),
        rows = transactions.also { require(it.size <= MAX_ROWS) { "too many rows" } }.map { t ->
            CasRow(LocalDate.parse(t.date), t.kind.also { require(it.matches(CODE)) }, t.amount?.let(::money), t.units?.let(Quantity::parse),
                t.nav?.let(UnitPrice::parse), t.balance?.let(Quantity::parse))
        }
    )

    private fun money(s: String): BigDecimal {
        require(s.matches(MONEY)) { "money must be a decimal string with 2 places" }
        return BigDecimal(s)
    }

    private companion object {
        val CODE = Regex("[A-Za-z_]{1,40}")
        val MONEY = Regex("\\d{1,16}\\.\\d{2}")
        const val MAX_FOLIOS = 500
        const val MAX_ROWS = 20_000
    }
}
