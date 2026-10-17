package dev.adminos.api.domain.ai

/** Kinds of personal data the masker finds (research §2.4). */
enum class PiiKind { EMAIL, VPA, PHONE, CARD_OR_ACCOUNT, NATIONAL_ID }

/** [text] with personal data masked, and what was found. */
data class Masked(val text: String, val found: Set<PiiKind>) {
    val isClean: Boolean get() = found.isEmpty()

    override fun toString() = "Masked(found=$found)" // the text may still be personal
}

/**
 * Masks personal data in Indian formats before anything reaches a model (D-67, research §2.4):
 * - emails → `<EMAIL>`; UPI IDs → `<VPA:provider>` (only the bank/app handle is kept);
 * - Indian mobile numbers (`98000 00012`, `+91-9800000012`, `09800000012`) → `<PHONE>`;
 * - Aadhaar-like 12-digit numbers (`2345 6789 0123`) and PANs (`ABCDE1234F`) → `<ID>`;
 * - card and account numbers (9–19 digits, spaced or not) → `XX` + the last 4 digits.
 * Regex masking is the baseline and isn't perfect, so features also **send less** (fields, not documents):
 * this is a guard, never the only line of defence.
 */
object PiiMasker {
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")
    private val VPA = Regex("[A-Za-z0-9._-]{2,}@([A-Za-z][A-Za-z0-9]{1,30})\\b")
    private val PAN = Regex("\\b[A-Za-z]{5}\\d{4}[A-Za-z]\\b")

    /** A run of digits, single spaces or dashes allowed between them, with an optional leading `+`. */
    private val DIGIT_RUN = Regex("(?<![\\dA-Za-z])(\\+)?\\d(?:[ -]?\\d){8,18}(?![\\dA-Za-z])")

    fun mask(text: String): Masked {
        val found = mutableSetOf<PiiKind>()
        var out = EMAIL.replace(text) { found += PiiKind.EMAIL; "<EMAIL>" }
        out = VPA.replace(out) { found += PiiKind.VPA; "<VPA:${it.groupValues[1].lowercase()}>" }
        out = PAN.replace(out) { found += PiiKind.NATIONAL_ID; "<ID>" }
        out = DIGIT_RUN.replace(out) { match ->
            val digits = match.value.filter(Char::isDigit)
            val kind = classify(digits, plus = match.groupValues[1].isNotEmpty())
            found += kind
            when (kind) {
                PiiKind.PHONE -> "<PHONE>"
                PiiKind.NATIONAL_ID -> "<ID>"
                else -> "XX" + digits.takeLast(4)
            }
        }
        return Masked(out, found)
    }

    private fun classify(digits: String, plus: Boolean): PiiKind = when {
        isMobile(digits, plus) -> PiiKind.PHONE
        digits.length == 12 && digits[0] in '2'..'9' -> PiiKind.NATIONAL_ID
        else -> PiiKind.CARD_OR_ACCOUNT
    }

    /** 10 digits starting 6–9, optionally after 91 (with `+` or as 12 digits) or a trunk 0. */
    private fun isMobile(digits: String, plus: Boolean): Boolean {
        fun mobile(d: String) = d.length == 10 && d[0] in '6'..'9'
        return when {
            plus -> digits.startsWith("91") && mobile(digits.drop(2))
            digits.length == 12 -> digits.startsWith("91") && mobile(digits.drop(2))
            digits.length == 11 -> digits.startsWith("0") && mobile(digits.drop(1))
            else -> mobile(digits)
        }
    }
}
