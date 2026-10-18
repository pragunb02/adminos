package dev.adminos.api.domain.wealth.imports

import dev.adminos.api.domain.wealth.MfPlan
import dev.adminos.api.domain.wealth.Quantity
import dev.adminos.api.domain.wealth.UnitPrice
import java.math.BigDecimal
import java.time.LocalDate

/**
 * A consolidated account statement as the parser service read it (AOS-805). Numbers are already exact types;
 * the folio number is the only identifier and lives in memory just long enough to be encrypted.
 */
data class ParsedCas(
    val readerId: String,
    val readerVersion: String,
    val issuer: String,
    val statementFrom: LocalDate?,
    val statementTo: LocalDate?,
    val folios: List<CasFolio>,
    val parseWarnings: Int
)

class CasFolio(val folioNumber: String, val amc: String, val schemes: List<CasScheme>) {
    /** Never prints the folio number (logs, exceptions, debuggers). */
    override fun toString() = "CasFolio(amc=$amc, schemes=${schemes.size})"
}

data class CasScheme(
    val name: String,
    val isin: String?,
    val amfiCode: String?,
    val schemeType: String?,
    val category: String?,
    val plan: MfPlan?,
    val openingUnits: Quantity,
    val closingUnits: Quantity,
    val valuationDate: LocalDate?,
    val nav: UnitPrice?,
    val value: BigDecimal?,
    val rows: List<CasRow>
)

/** One statement row: [kind] is the ledger vocabulary (`buy`, `sip_buy`…) or `unknown`. */
data class CasRow(val date: LocalDate, val kind: String, val amount: BigDecimal?, val units: Quantity?, val nav: UnitPrice?, val balance: Quantity?)

/** A parse failure with the parser's stable code. Never carries statement content. */
class CasParseException(val code: String, override val message: String, val clientError: Boolean) : RuntimeException(message)

/**
 * Port to the CAS reader in the statement parser service (D-37, D-79). The file and password are used in
 * memory only and never stored or logged.
 */
fun interface CasReader {
    suspend fun read(file: ByteArray, password: String?): ParsedCas
}
