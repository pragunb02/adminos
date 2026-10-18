package dev.adminos.api.domain.documents.reading

import dev.adminos.api.domain.ai.PiiKind
import dev.adminos.api.domain.ai.PiiMasker
import dev.adminos.api.domain.documents.DocumentCategory
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Whether a proposed value is grounded in the document's text (AOS-717, D-67): its evidence is an exact substring of
 * the text, and the value is in the evidence. This checks; it doesn't parse. The parser's rules find values, and this
 * makes sure a value (from the rules, or a model) is really there, so nothing invented can reach the owner:
 * - number and issuer: the value *is* in the evidence, as printed;
 * - date: the evidence holds its year, its day and its month (as a number or a month name);
 * - amount: the evidence's digits hold the amount's digits.
 */
object FieldGrounding {
    const val MAX_EVIDENCE = 200
    const val MAX_ISSUER = 120
    val MAX_AMOUNT = BigDecimal("100000000")
    private val NUMBER_SHAPE = Regex("[A-Z0-9][A-Z0-9 /\\-]{2,30}[A-Z0-9]")
    private val AMOUNT_SHAPE = Regex("\\d{1,9}(?:\\.\\d{1,2})?")
    private val TOKEN = Regex("\\d+|\\p{L}+")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    fun holds(field: DocumentField, value: String, evidence: String, text: String): Boolean {
        if (evidence.isBlank() || evidence.length > MAX_EVIDENCE || !text.contains(evidence)) return false
        return when (field) {
            DocumentField.NUMBER -> value == evidence && NUMBER_SHAPE.matches(value)
            DocumentField.ISSUER -> value.isNotBlank() && value == value.trim() && value.length <= MAX_ISSUER && evidence.contains(value)
            DocumentField.EXPIRES_ON -> date(value)?.let { dateIn(evidence, it) } == true
            DocumentField.AMOUNT -> amount(value)?.let { amountIn(evidence, it) } == true
        }
    }

    /** A strict ISO date in 2000..2100, or null. */
    fun date(value: String): LocalDate? = try {
        LocalDate.parse(value).takeIf { value.length == 10 && it.year in 2000..2100 }
    } catch (_: DateTimeParseException) {
        null
    }

    /** A positive decimal with at most 2 places, at most ₹10 crore, or null. */
    fun amount(value: String): BigDecimal? =
        value.takeIf { AMOUNT_SHAPE.matches(it) }?.toBigDecimal()?.takeIf { it.signum() > 0 && it <= MAX_AMOUNT }

    internal fun dateIn(evidence: String, d: LocalDate): Boolean {
        val tokens = TOKEN.findAll(evidence).map { it.value.lowercase() }.toList()
        val numbers = tokens.mapNotNull { it.toIntOrNull()?.takeIf { _ -> it.length <= 4 } }
        val month = MONTHS[d.monthValue - 1]
        return d.year in numbers && d.dayOfMonth in numbers.filter { it != d.year } &&
            (d.monthValue in numbers.filter { it != d.year } || tokens.any { it.length >= 3 && it.startsWith(month) })
    }

    internal fun amountIn(evidence: String, a: BigDecimal): Boolean {
        val digits = evidence.filter { it in '0'..'9' }
        val whole = a.toBigInteger().toString()
        val fraction = a.remainder(BigDecimal.ONE).movePointRight(2).toBigInteger().toInt()
        return digits.contains(whole) && (fraction == 0 || digits.contains(whole + "%02d".format(fraction)))
    }
}

/**
 * Identity documents (passport, Aadhaar, PAN, voter ID, driving licence) never go to any model (D-67; stricter than
 * "never to the cloud", AOS-717). A document counts as one when it is filed as Identity **or** its text looks like one,
 * so filing a passport under Other doesn't send it.
 */
object IdentityDocument {
    private val WORDS = Regex(
        "\\b(passport|aadhaa?r|unique\\s+identification\\s+authority|permanent\\s+account\\s+number|income\\s+tax\\s+department|" +
            "election\\s+commission|elector|voter|driving\\s+licen[cs]e|republic\\s+of\\s+india)\\b",
        RegexOption.IGNORE_CASE
    )

    fun isIdentity(category: DocumentCategory, text: String): Boolean =
        category == DocumentCategory.IDENTITY || WORDS.containsMatchIn(text) || PiiKind.NATIONAL_ID in PiiMasker.mask(text).found
}
