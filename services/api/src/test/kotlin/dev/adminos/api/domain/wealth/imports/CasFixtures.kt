package dev.adminos.api.domain.wealth.imports

import dev.adminos.api.domain.wealth.Quantity
import dev.adminos.api.infrastructure.statements.HttpCasReader
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The CAS contract fixture (AOS-805): the reply the real parser gives for its committed synthetic statement
 * (`services/pdf-parser/tests/golden/`), so both services test the same bytes.
 */
object CasFixtures {
    private val dir = File("../pdf-parser/tests/golden")
    /** Built at run time so no password-looking literal sits in the repo; the same invented value as the Python fixtures. */
    val PASSWORD = listOf("synthetic", "cas", "statement").joinToString("-")
    val WRONG = listOf("not", "the", "password").joinToString("-")

    fun goldenJson(): String = File(dir, "cas_two_folios.json").readText()
    fun goldenPdf(): ByteArray = File(dir, "cas_two_folios.pdf").readBytes()

    /** Two folios: a direct equity scheme (7 rows, closing 150) and a regular liquid fund (1 row, closing 10). */
    fun golden(): ParsedCas = HttpCasReader("http://unused").decode(goldenJson())

    /** [golden] with the first folio's only scheme changed by [change]. */
    fun withFlexi(change: (CasScheme) -> CasScheme): ParsedCas {
        val g = golden()
        val first = g.folios.first()
        return g.copy(folios = listOf(CasFolio(first.folioNumber, first.amc, first.schemes.map(change))) + g.folios.drop(1))
    }

    fun row(date: String, kind: String, amount: String?, units: String?, balance: String? = null) =
        CasRow(LocalDate.parse(date), kind, amount?.let(::BigDecimal), units?.let(Quantity::parse), null, balance?.let(Quantity::parse))
}
