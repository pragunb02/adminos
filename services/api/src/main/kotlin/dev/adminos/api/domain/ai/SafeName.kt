package dev.adminos.api.domain.ai

import java.lang.Character.UnicodeScript
import java.text.Normalizer

/**
 * Whether a short name (a merchant) may be shown to a model at all (D-33, D-67; AOS-702 security reviews B1).
 *
 * Masking is best effort, so for names we **withhold** instead of masking whenever a name could be hiding a
 * number or an identifier. The name is NFKC-normalised and read **by code point** (so digits and letters
 * outside the Basic Multilingual Plane count), then withheld for:
 * - control or invisible format characters (bidi overrides, zero-width joiners);
 * - any digit outside ASCII `0-9` (Devanagari, mathematical…, after NFKC folded fullwidth to ASCII);
 * - [MAX_DIGITS] or more digits in total, however they are separated;
 * - letters outside [ALLOWED_SCRIPTS] (a PAN written in Cyrillic or Greek look-alikes), or from more than one script;
 * - a PAN shape in letters of any script (`ABCDE1234F`);
 * - anything [PiiMasker] recognises.
 * A real business name needs none of these, so the cost is that a rare odd name stays "Other".
 */
object SafeName {
    /** Business names carry at most a few digits ("24x7", "7-Eleven"); a phone or card has many more. */
    const val MAX_DIGITS = 5

    /** Merchant names here are written in Latin or Devanagari letters. */
    val ALLOWED_SCRIPTS: Set<UnicodeScript> = setOf(UnicodeScript.LATIN, UnicodeScript.DEVANAGARI)

    enum class Problem { UNSAFE_CHARACTERS, NON_ASCII_DIGITS, TOO_MANY_DIGITS, UNEXPECTED_SCRIPT, MIXED_SCRIPTS, PII }

    /** Five letters, four digits, one letter, in any script: the PAN shape. */
    private val ANY_SCRIPT_PAN = Regex("(?<!\\p{L})\\p{L}{5}\\p{Nd}{4}\\p{L}(?!\\p{L})")

    /** Why [name] must not be sent, or null when it may be. */
    fun problem(name: String): Problem? {
        val raw = name.codePoints().toArray()
        if (raw.any(::isUnsafe)) return Problem.UNSAFE_CHARACTERS
        val normal = Normalizer.normalize(name, Normalizer.Form.NFKC)
        val points = normal.codePoints().toArray()
        if (points.any(::isUnsafe)) return Problem.UNSAFE_CHARACTERS
        if (points.any { Character.isDigit(it) && it !in '0'.code..'9'.code }) return Problem.NON_ASCII_DIGITS
        if (points.count { it in '0'.code..'9'.code } >= MAX_DIGITS) return Problem.TOO_MANY_DIGITS
        val scripts = scriptsOf(points)
        if (scripts.any { it !in ALLOWED_SCRIPTS }) return Problem.UNEXPECTED_SCRIPT
        if (scripts.size > 1) return Problem.MIXED_SCRIPTS
        if (ANY_SCRIPT_PAN.containsMatchIn(normal) || !PiiMasker.mask(normal).isClean) return Problem.PII
        return null
    }

    fun isSafe(name: String): Boolean = problem(name) == null

    private fun isUnsafe(cp: Int) = Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT.toInt()

    /** The scripts of the letters (COMMON and INHERITED, like digits and accents, don't count). */
    private fun scriptsOf(points: IntArray): Set<UnicodeScript> = points
        .filter { Character.isLetter(it) }
        .map { UnicodeScript.of(it) }
        .filter { it != UnicodeScript.COMMON && it != UnicodeScript.INHERITED }
        .toSet()
}
