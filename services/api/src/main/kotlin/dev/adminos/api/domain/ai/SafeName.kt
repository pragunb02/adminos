package dev.adminos.api.domain.ai

import java.lang.Character.UnicodeScript

/**
 * Whether a short name (a merchant) may be shown to a model at all (D-33, D-67; AOS-702 security review B1).
 *
 * Masking is best effort, so for names we **withhold** instead of masking whenever a name could be hiding a
 * number or an identifier:
 * - any digit outside ASCII `0-9` (fullwidth, Devanagari…): a number written to slip past `\d`;
 * - [MAX_DIGITS] or more digits in total, however they are separated (`98.00/00_00 12`);
 * - letters from more than one script (`ΑBCDE…` with a Greek or Cyrillic look-alike);
 * - control or invisible format characters (bidi overrides, zero-width joiners);
 * - anything [PiiMasker] recognises.
 * A real business name needs none of these, so the cost is that a rare odd name stays "Other".
 */
object SafeName {
    /** Business names carry at most a few digits ("24x7", "7-Eleven"); a phone or card has many more. */
    const val MAX_DIGITS = 5

    enum class Problem { UNSAFE_CHARACTERS, NON_ASCII_DIGITS, TOO_MANY_DIGITS, MIXED_SCRIPTS, PII }

    /** Why [name] must not be sent, or null when it may be. */
    fun problem(name: String): Problem? = when {
        name.any(::isUnsafe) -> Problem.UNSAFE_CHARACTERS
        name.any { Character.isDigit(it) && it !in '0'..'9' } -> Problem.NON_ASCII_DIGITS
        name.count { it in '0'..'9' } >= MAX_DIGITS -> Problem.TOO_MANY_DIGITS
        scriptsOf(name).size > 1 -> Problem.MIXED_SCRIPTS
        !PiiMasker.mask(name).isClean -> Problem.PII
        else -> null
    }

    fun isSafe(name: String): Boolean = problem(name) == null

    private fun isUnsafe(c: Char) = Character.isISOControl(c) || Character.getType(c) == Character.FORMAT.toInt()

    /** The scripts of the letters in [s] (COMMON and INHERITED, like digits and accents, don't count). */
    private fun scriptsOf(s: String): Set<UnicodeScript> = s.codePoints().toArray()
        .filter { Character.isLetter(it) }
        .map { UnicodeScript.of(it) }
        .filter { it != UnicodeScript.COMMON && it != UnicodeScript.INHERITED }
        .toSet()
}
