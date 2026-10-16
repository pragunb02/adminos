package dev.adminos.api.domain.ingestion.statements

import java.math.BigDecimal
import java.time.LocalDate

/** One statement line as returned by the parser service. Exactly one of [debit]/[credit] is set. */
data class StatementRow(
    val date: LocalDate,
    val description: String,
    val debit: BigDecimal?,
    val credit: BigDecimal?,
    val balance: BigDecimal?,
    val reference: String?
)

data class ParsedStatement(
    val bankCode: String?,
    val accountLast4: String?,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
    val rows: List<StatementRow>,
    val skippedRows: Int,
    val warnings: List<String>
)

/** A parse failure with the parser's stable code (e.g. PASSWORD_REQUIRED). Never carries statement content. */
class StatementParseException(val code: String, override val message: String, val clientError: Boolean) : RuntimeException(message)

/** Port to the statement parser (D-37). The file and password are used in memory only. */
interface StatementParser {
    suspend fun parse(file: ByteArray, filename: String, password: String?): ParsedStatement
}
