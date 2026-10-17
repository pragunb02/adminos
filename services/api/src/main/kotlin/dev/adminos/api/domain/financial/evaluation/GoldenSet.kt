package dev.adminos.api.domain.financial.evaluation

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionType

/**
 * Reads the labelled golden set (AOS-511): `src/main/resources/categorization/golden.csv`. It ships with the API
 * because it is also the learned categoriser's synthetic prior (AOS-707); the evaluation never scores a model
 * on rows it was trained on (cross-validation).
 * Plain comma-separated values, one row per case, `#` lines are comments; fields never contain commas.
 * Any malformed row fails loudly with its line number: a silently skipped row would inflate the score.
 */
object GoldenSet {
    const val RESOURCE = "/categorization/golden.csv"
    private val HEADER = listOf("id", "merchant_raw", "upi_vpa", "payment_method", "direction", "expected_category", "expected_subcategory", "tags")

    fun load(resource: String = RESOURCE): List<LabelledCase> {
        val text = GoldenSet::class.java.getResource(resource)?.readText() ?: throw IllegalStateException("golden set not found: $resource")
        return parse(text)
    }

    fun parse(text: String): List<LabelledCase> {
        val lines = text.lines().withIndex().filter { (_, l) -> l.isNotBlank() && !l.trimStart().startsWith("#") }
        require(lines.isNotEmpty() && lines.first().value.split(',') == HEADER) { "golden set: the first row must be the header $HEADER" }
        val cases = lines.drop(1).map { (i, line) -> parseRow(i + 1, line) }
        val duplicate = cases.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicate.isEmpty()) { "golden set: duplicate ids $duplicate" }
        return cases
    }

    private fun parseRow(lineNo: Int, line: String): LabelledCase {
        val f = line.split(',').map { it.trim() }
        require(f.size == HEADER.size) { "golden set line $lineNo: expected ${HEADER.size} fields, found ${f.size}" }
        fun blankToNull(s: String) = s.takeIf { it.isNotEmpty() }
        try {
            return LabelledCase(
                id = f[0],
                input = CaseInput(
                    merchantRaw = blankToNull(f[1]),
                    upiVpa = blankToNull(f[2]),
                    paymentMethod = blankToNull(f[3])?.let { PaymentMethod.valueOf(it) },
                    type = TransactionType.valueOf(f[4])
                ),
                expected = Label(TransactionCategory.valueOf(f[5]), blankToNull(f[6])),
                tags = f[7].split(' ').filter { it.isNotBlank() }.toSet()
            )
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("golden set line $lineNo (${f[0]}): ${e.message}", e)
        }
    }
}
