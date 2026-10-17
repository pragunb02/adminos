package dev.adminos.api.domain.coach.lenses

import dev.adminos.api.domain.coach.CoachFixture.txn
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AOS-901: UPI payments to people (settle-ups) are told apart from payments to shops. */
class PersonToPersonTest {
    private val p2p = PersonToPerson()

    @Test
    fun `personal app handles and mobile numbers are people`() {
        for ((name, vpa) in listOf(
            "Rohit Sharma" to "rohit.sharma92@oksbi", "Priya Nair" to "priya.nair@okhdfcbank", "Ankit Verma" to "9800000003@ybl",
            "Kavya Iyer" to "kavya.iyer@okaxis", "Mohan Kumar" to "mohankumar1985@paytm", "Sneha" to "sneha.r@okicici",
            "Arjun" to "9876543210@axisbank", "Neha" to "neha@upi"
        )) assertTrue(p2p.isPersonToPerson(txn(name, vpa = vpa)), vpa)
    }

    @Test
    fun `merchant QR and gateway handles are shops`() {
        for ((name, vpa) in listOf(
            "Chai Point" to "paytmqr281005050101abcd@paytm", "Corner Store" to "q123456789@ybl", "Sharma Kirana" to "bharatpe.90000000@fbpe",
            "Toit" to "gpay-11234567890@okbizaxis", "Local Bar" to "localbar.rzp@icici", "Cafe" to "cafe@payu", "Shop" to "paytm.s1abc@pty",
            "Big Merchant" to "merchant.123@okaxis", "Bistro" to "bistro@cashfree"
        )) assertFalse(p2p.isPersonToPerson(txn(name, vpa = vpa)), vpa)
    }

    @Test
    fun `a catalogue brand is never a person whatever its handle`() {
        assertFalse(p2p.isPersonToPerson(txn("Zepto", vpa = "zepto@ybl")))
    }

    @Test
    fun `bank handles and payments without a UPI ID are not judged people`() {
        assertFalse(p2p.isPersonToPerson(txn("Delhi Public School", vpa = "dpsfees@icici")))
        assertFalse(p2p.isPersonToPerson(txn("Vikram Singh")))
        assertFalse(p2p.isPersonToPerson(txn("Odd", vpa = "no-at-sign")))
        assertFalse(p2p.isPersonToPerson(txn("Blank", vpa = "  ")))
    }
}
