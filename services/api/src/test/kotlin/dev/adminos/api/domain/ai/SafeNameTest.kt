package dev.adminos.api.domain.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** AOS-702 security review B1: names that could hide a number or an identifier are withheld, not sent. */
class SafeNameTest {
    @Test
    fun `ordinary business names may be sent`() {
        listOf("Swiggy", "BigBasket", "7-Eleven", "Cafe 24x7", "Haldiram Foods", "\u0939\u0932\u094D\u0926\u0940\u0930\u093E\u092E") // last: Devanagari only
            .forEach { assertNull(SafeName.problem(it), it) }
    }

    @Test
    fun `digits outside ASCII are withheld`() {
        assertEquals(SafeName.Problem.NON_ASCII_DIGITS, SafeName.problem("Shop \uFF19\uFF18\uFF10\uFF10"))
        assertEquals(SafeName.Problem.NON_ASCII_DIGITS, SafeName.problem("Shop \u096F\u096E"))
    }

    @Test
    fun `five or more digits in total are withheld however they are split`() {
        listOf("Shop 98.00/00_00 12", "A 1 2 3 4 5", "Pay 12-34-5").forEach { assertEquals(SafeName.Problem.TOO_MANY_DIGITS, SafeName.problem(it), it) }
        assertNull(SafeName.problem("Store 1234"))
    }

    @Test
    fun `letters from mixed scripts are withheld (look-alikes)`() {
        assertEquals(SafeName.Problem.MIXED_SCRIPTS, SafeName.problem("\u0410BCDE Traders")) // Cyrillic A + Latin
        assertEquals(SafeName.Problem.MIXED_SCRIPTS, SafeName.problem("Net\u0066lix \u03BF")) // Latin + Greek omicron
    }

    @Test
    fun `control and invisible characters are withheld`() {
        assertEquals(SafeName.Problem.UNSAFE_CHARACTERS, SafeName.problem("Shop\u202Eevil"))
        assertEquals(SafeName.Problem.UNSAFE_CHARACTERS, SafeName.problem("Sho\u200Bp"))
    }

    @Test
    fun `anything the masker recognises is withheld`() {
        assertEquals(SafeName.Problem.PII, SafeName.problem("me@example.com"))
    }
}
