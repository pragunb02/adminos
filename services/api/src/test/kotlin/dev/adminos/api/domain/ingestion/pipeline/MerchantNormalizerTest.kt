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
        assertEquals("JioMart", n.normalize("JIOMART GROCERY")) // its own brand, not "Jio"
        assertEquals("Motorola Store", n.normalize("MOTOROLA STORE")) // not "Ola"
    }

    @Test
    fun `upi vpa handle identifies the brand when the descriptor does not`() {
        assertEquals("Swiggy", n.normalize(raw = "UPI-123456789", upiVpa = "swiggy@icici"))
    }

    @Test
    fun `a UPI ID inside the narration is not read as the brand of its provider`() {
        // AOS-511 regression: "annapoorna@paytm" made every Paytm-handle payee "Paytm" (a wallet, so Other).
        assertEquals("Annapoorna Food Court",
            n.normalize("UPI-ANNAPOORNA FOOD COURT-annapoorna@paytm-PYTM0123456-428911100024-PAY", upiVpa = "annapoorna@paytm"))
        assertEquals("Swiggy", n.normalize("UPI-123456789-swiggy@paytm-PAY")) // a brand in the handle still counts
        assertEquals("Paytm", n.normalize("UPI-PAYTM-paytmqr@paytm-PAY")) // and so does the brand in the narration
    }

    @Test
    fun `unknown merchants are title cased and blanks are null`() {
        assertEquals("Ram Tea Stall", n.normalize("RAM  TEA stall"))
        assertNull(n.normalize("  "))
        assertNull(n.normalize(null))
    }

    // ── AOS-206: catalogue + narration cleaning ──

    @Test
    fun `legal entity names map to the brand people know`() {
        assertEquals("Swiggy", n.normalize("BUNDL TECHNOLOGIES PVT LTD"))
        assertEquals("Ola", n.normalize("ANI TECHNOLOGIES PRIVATE LIMITED"))
        assertEquals("BookMyShow", n.normalize("BIGTREE ENTERTAINMENT"))
        assertEquals("IndiGo", n.normalize("INTERGLOBE AVIATION LTD"))
        assertEquals("Cult", n.normalize("CULT.FIT GURGAON"))
        assertEquals("Apple", n.normalize("APPLE.COM/BILL"))
    }

    @Test
    fun `payment gateway prefixes do not hide the merchant`() {
        assertEquals("Nykaa", n.normalize("PAYU*NYKAA"))
        assertEquals("Sharma Sweets", n.normalize("RAZ*SHARMA SWEETS"))
    }

    @Test
    fun `bank narrations give the payee's name`() {
        assertEquals("Ramu Chaiwala", n.normalize("UPI-RAMU CHAIWALA-ramu@okicici-ICIC0001234-123456789012-PAYMENT"))
        assertEquals("Ramu Chai", n.normalize("UPI/DR/123456789012/RAMU CHAI/YESB/ramu@ybl/Payment"))
        assertEquals("Ravi Kumar", n.normalize("UPI/123456789012/RAVI KUMAR/ravi.k@okaxis/Axis Bank"))
        assertEquals("Blue Bottle Cafe", n.normalize("POS 512345XXXXXX1234 BLUE BOTTLE CAFE BANGALORE"))
        assertEquals("Back To Basics", n.normalize("BACK TO BASICS"), "inner words are kept")
    }

    @Test
    fun `the UPI handle is the name when the narration has none`() {
        assertEquals("Ramu Chai", n.normalize(raw = "UPI-123456789012", upiVpa = "ramu.chai@ybl"))
        assertEquals("Swiggy", n.normalize(raw = null, upiVpa = "bundl.swiggy@axisbank"))
    }
}
