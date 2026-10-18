package dev.adminos.api.domain.ingestion.fallback

import dev.adminos.api.domain.ai.AiReasonCode
import dev.adminos.api.domain.ai.PiiKind
import dev.adminos.api.domain.ai.PiiMasker
import java.text.Normalizer

/** What [MessageRedactor] made of a message: the only text a model may see, or a refusal. */
sealed interface RedactedMessage {
    /** [text] is masked and flattened to one line; [found] says what was masked (kinds only). */
    class Ready(val text: String, val found: Set<PiiKind>) : RedactedMessage {
        override fun toString() = "Ready(${text.length} chars, found=$found)" // never print the text
    }

    /** Not sent at all: see the codes on [MessageRedactor]. */
    data class Withheld(val code: AiReasonCode) : RedactedMessage
}

/**
 * The message-reading feature's redactor (AOS-705, D-67, D-106): a pure function from a bank message to the
 * only text that may leave AdminOS. It **withholds** a message, rather than trying to clean it, when it is:
 * - longer than [MAX_CHARS] (bank alerts are short; a long text is not an alert, or is stuffed) → `too_long`;
 * - carrying control or invisible format characters (bidi overrides, zero-width joiners) → `unsafe_characters`;
 * - written with digits outside ASCII `0-9` after NFKC (fullwidth folds to ASCII; Devanagari or mathematical
 *   digits don't): a value the checks can't compare → `non_ascii_digits`;
 * - talking to the model ("ignore previous instructions", "system prompt", JSON or markup) → `suspected_injection`.
 *   No bank writes that, so it is withheld instead of being argued with.
 * Otherwise it is flattened to one line and masked: [PiiMasker] (emails, VPAs, phones, Aadhaar-like numbers, PANs,
 * and card or account numbers of 9+ digits → `XX` + last 4), then any other bare run of 5–8 digits that isn't a
 * money figure (short account numbers, customer ids) → `XX` + last 4. Amounts, dates and last-4 digits stay,
 * because the answer is checked against them.
 */
object MessageRedactor {
    const val MAX_CHARS = 1_000

    val TOO_LONG = AiReasonCode("too_long")
    val UNSAFE_CHARACTERS = AiReasonCode("unsafe_characters")
    val NON_ASCII_DIGITS = AiReasonCode("non_ascii_digits")
    val SUSPECTED_INJECTION = AiReasonCode("suspected_injection")

    private val I = RegexOption.IGNORE_CASE
    private val INJECTION = listOf(
        Regex("\\b(ignore|disregard|forget|override)\\b.{0,40}\\b(instruction|instructions|previous|above|prompt|rules|system)\\b", I),
        Regex("\\b(system prompt|developer message|you are (?:an? )?(?:ai|assistant|model|chatbot)|as an ai|jailbreak|respond (?:only )?with|reply (?:only )?with|output (?:only )?json|new instructions?)\\b", I),
        Regex("[{}<>\\[\\]`]")
    )
    private val WHITESPACE = Regex("\\s+")

    /** A bare digit run of 5–8 digits, not part of a longer number, a decimal or an Indian-grouped amount. */
    private val SHORT_NUMBER = Regex("(?<![0-9.,])[0-9]{5,8}(?![0-9]|[.,][0-9])")
    private val CURRENCY_BEFORE = Regex("(?:Rs\\.?|INR|₹)\\s*$", I)
    private val CURRENCY_AFTER = Regex("^\\s*(?:INR|Rs\\b|rupees\\b)", I)

    fun redact(raw: String): RedactedMessage {
        if (raw.length > MAX_CHARS * 2) return RedactedMessage.Withheld(TOO_LONG) // bound the work before normalising
        if (raw.codePoints().anyMatch(::isUnsafe)) return RedactedMessage.Withheld(UNSAFE_CHARACTERS)
        val normal = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        if (normal.codePoints().anyMatch(::isUnsafe)) return RedactedMessage.Withheld(UNSAFE_CHARACTERS)
        if (normal.codePoints().anyMatch { Character.isDigit(it) && it !in '0'.code..'9'.code }) return RedactedMessage.Withheld(NON_ASCII_DIGITS)
        val flat = WHITESPACE.replace(normal, " ").trim()
        if (flat.length > MAX_CHARS) return RedactedMessage.Withheld(TOO_LONG)
        if (INJECTION.any { it.containsMatchIn(flat) }) return RedactedMessage.Withheld(SUSPECTED_INJECTION)

        val masked = PiiMasker.mask(flat)
        var found = masked.found
        val text = SHORT_NUMBER.replace(masked.text) { m ->
            val before = masked.text.substring(0, m.range.first)
            val after = masked.text.substring(m.range.last + 1)
            if (CURRENCY_BEFORE.containsMatchIn(before) || CURRENCY_AFTER.containsMatchIn(after)) m.value else {
                found = found + PiiKind.CARD_OR_ACCOUNT
                "XX" + m.value.takeLast(4)
            }
        }
        return RedactedMessage.Ready(text, found)
    }

    /** Control characters (newlines and tabs are whitespace, kept until flattening) and invisible format characters. */
    private fun isUnsafe(cp: Int): Boolean =
        (Character.isISOControl(cp) && cp != '\n'.code && cp != '\r'.code && cp != '\t'.code) || Character.getType(cp) == Character.FORMAT.toInt()
}
