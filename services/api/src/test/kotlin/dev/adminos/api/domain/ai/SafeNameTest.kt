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
        // Fullwidth digits fold to ASCII under NFKC, so they count towards the digit limit like any other digit.
        assertEquals(SafeName.Problem.TOO_MANY_DIGITS, SafeName.problem("Shop \uFF19\uFF18\uFF10\uFF10\uFF10"))
        assertEquals(SafeName.Problem.NON_ASCII_DIGITS, SafeName.problem("Shop \u096F\u096E"))
    }

    @Test
    fun `five or more digits in total are withheld however they are split`() {
        listOf("Shop 98.00/00_00 12", "A 1 2 3 4 5", "Pay 12-34-5").forEach { assertEquals(SafeName.Problem.TOO_MANY_DIGITS, SafeName.problem(it), it) }
        assertNull(SafeName.problem("Store 1234"))
    }

    @Test
    fun `digits beyond the basic plane are read by code point and withheld (review B1a)`() {
        val mathDigits = "\uD835\uDFD7\uD835\uDFD6" // MATHEMATICAL BOLD DIGIT NINE, EIGHT: surrogate pairs
        // NFKC folds them to ASCII 9 and 8, so they count towards the digit limit, however they are separated.
        assertEquals(SafeName.Problem.TOO_MANY_DIGITS, SafeName.problem("Shop $mathDigits,$mathDigits,\uD835\uDFD8"))
        val adlam = "\uD83A\uDD50\uD83A\uDD51" // ADLAM DIGIT ZERO, ONE: not folded by NFKC
        assertEquals(SafeName.Problem.NON_ASCII_DIGITS, SafeName.problem("Shop $adlam"))
    }

    @Test
    fun `a PAN written wholly in look-alike letters of another script is withheld (review B1b)`() {
        val cyrillicPan = "\u0410\u0412\u0421\u0415\u041A1234\u041C" // А В С Е К 1234 М, one script only
        assertEquals(SafeName.Problem.UNEXPECTED_SCRIPT, SafeName.problem(cyrillicPan))
        assertEquals(SafeName.Problem.UNEXPECTED_SCRIPT, SafeName.problem("\u0391\u0392\u0395\u0396\u03971234\u039A")) // Greek
        assertEquals(SafeName.Problem.PII, SafeName.problem("ABCDE1234F"))
    }

    @Test
    fun `letters from mixed scripts are withheld (look-alikes)`() {
        assertEquals(SafeName.Problem.UNEXPECTED_SCRIPT, SafeName.problem("\u0410BCDE Traders")) // Cyrillic A + Latin
        assertEquals(SafeName.Problem.UNEXPECTED_SCRIPT, SafeName.problem("Net\u0066lix \u03BF")) // Latin + Greek omicron
        assertEquals(SafeName.Problem.MIXED_SCRIPTS, SafeName.problem("Haldiram \u0939\u0932")) // Latin + Devanagari
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
