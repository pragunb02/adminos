package dev.adminos.api.domain.ingestion.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MerchantNormalizerTest {
    private val n = MerchantNormalizer()

    @Test
    fun `known brands are recognised inside noisy descriptors`() {
        assertEquals("Swiggy", n.normalize("SWIGGY*ORDER 12345 BLR"))
        assertEquals("Amazon", n.normalize("AMZN Mktp IN"))
    }

    @Test
    fun `longest alias wins`() {
        assertEquals("Uber Eats", n.normalize("UBER EATS INDIA"))
        assertEquals("Amazon Prime", n.normalize("AMAZON PRIME MEMBERSHIP"))
    }

    @Test
    fun `aliases match whole words only`() {
        assertEquals("Coca Cola Store", n.normalize("COCA COLA STORE")) // not "Ola"
        assertEquals("Jiomart Grocery", n.normalize("JIOMART GROCERY")) // not "Jio"
    }

    @Test
    fun `upi vpa handle identifies the brand when the descriptor does not`() {
        assertEquals("Swiggy", n.normalize(raw = "UPI-123456789", upiVpa = "swiggy@icici"))
    }

    @Test
    fun `unknown merchants are title cased and blanks are null`() {
        assertEquals("Ram Tea Stall", n.normalize("RAM  TEA stall"))
        assertNull(n.normalize("  "))
        assertNull(n.normalize(null))
    }
}
