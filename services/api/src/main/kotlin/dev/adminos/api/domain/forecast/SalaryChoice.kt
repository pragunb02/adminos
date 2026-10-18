package dev.adminos.api.domain.forecast

import java.math.BigDecimal
import java.time.Instant

/** How the owner answered the detected salary (AOS-722): kept as detected, or changed. */
enum class SalaryChoiceKind { CONFIRMED, CORRECTED }

/**
 * The owner's salary (AOS-722): who pays it ([payer]; null = credits that come without a name), the usual [day]
 * of the month (clamped to short months) and the usual [amount]. [SalaryDetector] uses it instead of guessing.
 *
 * The payer is shown back as text, so it is plain printable text: trimmed, 1..[MAX_PAYER] characters, no control,
 * format (bidi overrides, zero-width), separator or lone-surrogate characters.
 */
data class SalaryChoice(
    val payer: String?,
    val day: Int,
    val amount: BigDecimal,
    val kind: SalaryChoiceKind,
    val updatedAt: Instant
) {
    init {
        require(day in 1..31) { "day: a day of the month, 1 to 31" }
        require(amount.signum() > 0) { "amount: more than zero" }
        require(amount.stripTrailingZeros().scale() <= 2) { "amount: at most two decimal places" }
        require(amount <= MAX_AMOUNT) { "amount: at most $MAX_AMOUNT" }
        if (payer != null) {
            require(payer == payer.trim()) { "payer: no spaces at the start or end" }
            require(payer.length in 1..MAX_PAYER) { "payer: 1 to $MAX_PAYER characters" }
            require(payer.codePoints().noneMatch(::isHidden)) { "payer: letters, digits and punctuation only (no control or invisible characters)" }
        }
    }

    // The payer and the amount say who pays the owner and how much: never in logs.
    override fun toString(): String = "SalaryChoice(kind=$kind, day=$day, updatedAt=$updatedAt)"

    companion object {
        const val MAX_PAYER = 100
        val MAX_AMOUNT = BigDecimal("1000000000.00")

        /** A choice from the owner's input: the payer is trimmed (blank is refused, not read as "no payer"); amounts keep two places. */
        fun of(payer: String?, day: Int, amount: BigDecimal, kind: SalaryChoiceKind, at: Instant): SalaryChoice =
            SalaryChoice(payer?.trim(), day, amount.stripTrailingZeros().let { if (it.scale() < 2) it.setScale(2) else it }, kind, at)

        private fun isHidden(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
            Character.UNASSIGNED, Character.PRIVATE_USE -> true
            else -> false
        }
    }
}
