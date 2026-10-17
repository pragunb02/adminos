package dev.adminos.api.domain.ingestion.sms

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.ingestion.events.IngestDirection
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * AOS-213 (promised credits), AOS-214 (Dr/Cr alerts that show a balance) and AOS-215 (direction from Dr/Cr and the
 * account-anchored phrase, D-43). Every message is SYNTHETIC, in the shape of common Indian bank SMS; no real
 * message, account, phone number or reference is used.
 */
class SmsDirectionAndPromisesTest {

    private val registry = SmsParserRegistry()
    private fun parse(sender: String, text: String) = registry.parse(SmsMessage(sender, text, Instant.parse("2026-10-16T03:42:00Z")))
    private fun txn(sender: String, text: String) = assertIs<SmsParseResult.Transaction>(parse(sender, text), text).parsed
    private fun reason(sender: String, text: String) = assertIs<SmsParseResult.Ignored>(parse(sender, text), text).reason
    private fun money(s: String) = BigDecimal(s).setScale(2)

    private fun assertTxn(direction: IngestDirection, amount: String, t: ParsedSmsTransaction) {
        assertEquals(direction, t.direction)
        assertEquals(money(amount), t.amount)
    }

    // ── AOS-213: a promised credit is not a transaction ──

    @Test
    fun `promised cashback and refunds are dropped in every bank format`() {
        val promises = listOf(
            "AX-HDFCBK-S" to "Rs.50 cashback will be credited to your HDFC Bank A/c XX1234 within 3 working days. -HDFC Bank",
            "JD-CBSSBI-S" to "Dear Customer, refund of Rs 1,250.00 for your txn at IRCTC shall be credited to your a/c XX5678 in 5-7 days. -SBI",
            "VM-ICICIB-S" to "Rs 75 cashback to be credited to ICICI Bank Card XX4321 by 30-Oct-26 on your spend at AMAZON.",
            "AX-AXISBK-S" to "You are eligible for cashback of INR 100.00 on your Axis Bank Card XX2468. It will reflect in your next statement.",
            "BZ-KOTAKB-S" to "Rs 200 cashback credited within 7 days of your UPI payment of Rs 2,000 to merchant@kotak",
            "AD-YESBNK-S" to "Refund of Rs 499.00 has been initiated and will be credited to your A/c XX1357 within 5-7 working days.",
            "AD-YESBNK-S" to "Rs 300 will get credited to your account once the merchant confirms the reversal",
            "AD-YESBNK-S" to "Your refund of Rs 899 is yet to be credited. Track it on the app."
        )
        for ((sender, text) in promises) assertEquals(SmsParseResult.PROMISED_CREDIT, reason(sender, text), text)
    }

    @Test
    fun `a real debit beside a promised cashback is still the debit, without the promised amount`() {
        val axis = txn("AX-AXISBK-S", "Rs 2,000 spent on Axis Bank Card XX2468 at FLIPKART on 16-10-26. Cashback of Rs 100 will be credited within 30 days.")
        assertTxn(IngestDirection.DEBIT, "2000", axis)
        assertEquals("2468", axis.accountHint)
        val hdfc = txn("AX-HDFCBK-S", "Rs.249.00 paid to swiggy@axl from HDFC Bank A/c XX1234; Rs.25 cashback to be credited by 20-10-26")
        assertTxn(IngestDirection.DEBIT, "249.00", hdfc)
        assertEquals("swiggy@axl", hdfc.upiVpa)
    }

    @Test
    fun `a real credit beside a promised one is still the credit`() {
        assertTxn(IngestDirection.CREDIT, "5000",
            txn("JD-CBSSBI-S", "Rs 5,000.00 credited to A/c XX5678 on 16-10-26 by NEFT. Additional reward of Rs 50 will be credited in 3 days. -SBI"))
        assertTxn(IngestDirection.CREDIT, "1000",
            txn("BZ-KOTAKB-S", "Rs 1,000 received in your Kotak A/c X1234 from ACME PAYROLL and Rs 20 cashback will be credited later"))
    }

    @Test
    fun `cashback that has been credited is a real credit`() {
        assertTxn(IngestDirection.CREDIT, "50", txn("AX-HDFCBK-S", "Rs.50 cashback credited to your HDFC Bank A/c XX1234 on 16-10-26"))
        assertTxn(IngestDirection.CREDIT, "1250", txn("JD-CBSSBI-S", "Refund of Rs 1,250.00 has been credited to your a/c XX5678 on 16Oct26. -SBI"))
    }

    @Test
    fun `upcoming debits are still dropped as before`() {
        assertEquals("upcoming payment notice", reason("AX-HDFCBK-S", "Rs.12,500 will be debited from A/c XX1234 on 20-10-26 for your EMI."))
    }

    @Test
    fun `promise stripping keeps every other clause exactly as written`() {
        assertNull(PromisedCredits.strip("Rs 500 debited from A/c XX1234"))
        assertEquals("Rs 2,000 spent at X on 16-10-26..", PromisedCredits.strip("Rs 2,000 spent at X on 16-10-26. Cashback will be credited."))
        assertEquals("Paid via UPI from A/c no. XX1234;", PromisedCredits.strip("Paid via UPI from A/c no. XX1234; refund to be credited"))
    }

    // ── AOS-214: a transaction that also shows the balance is kept ──

    @Test
    fun `Dr and Cr alerts that show Avl Bal are transactions, not balance alerts`() {
        val sbi = txn("JD-CBSSBI-S", "Your A/c XX5678 is Dr. with Rs.1,200.00 on 16Oct26 by UPI. Avl Bal Rs.8,800.00 -SBI")
        assertTxn(IngestDirection.DEBIT, "1200.00", sbi); assertEquals("5678", sbi.accountHint)

        val kotak = txn("BZ-KOTAKB-S", "A/c X1234 Cr. with INR 3,000.00 on 16-10-26 from ACME PAYROLL. Avl Bal INR 13,000.00")
        assertTxn(IngestDirection.CREDIT, "3000.00", kotak)

        val icici = txn("VK-ICICIB-S", "ICICI Bank Acct XX234 Dr. INR 249.00 on 16-Oct-26 at SWIGGY. Avl Bal INR 9,751.00")
        assertTxn(IngestDirection.DEBIT, "249.00", icici); assertNull(icici.accountHint)

        val hdfc = txn("AX-HDFCBK-S", "Txn Rs.799.00 Dr from HDFC Bank A/c XX1234 at NETFLIX on 16-10-26. Avl bal: Rs.20,000.00")
        assertTxn(IngestDirection.DEBIT, "799.00", hdfc)

        val axis = txn("AX-AXISBK-S", "Debit INR 450.00 A/c no. XX2468 16-10-26 UPI/P2M/123456789012/ZOMATO Avl Bal INR 5,550.00")
        assertTxn(IngestDirection.DEBIT, "450.00", axis); assertEquals("ZOMATO", axis.merchantRaw)

        assertTxn(IngestDirection.DEBIT, "500.00",
            txn("AD-YESBNK-S", "Rs.500.00 Dr. from A/C XXXXXX1357 to swiggy@icici. Ref:123456789012. Avl Bal:Rs 4,500.00"))
    }

    @Test
    fun `withdrawals, payments and spends that show the balance are kept`() {
        assertTxn(IngestDirection.DEBIT, "2000", txn("JD-CBSSBI-S", "Rs 2,000 withdrawn at ATM from A/c XX5678 on 16Oct26. Avl Bal Rs 3,000.00 -SBI"))
        assertTxn(IngestDirection.DEBIT, "1500", txn("AX-HDFCBK-S", "Rs 1,500 paid to ELECTRICITY BOARD from A/c XX1234. Available balance Rs 18,500"))
        assertTxn(IngestDirection.CREDIT, "10000", txn("BZ-KOTAKB-S", "Credit of Rs 10,000 to Kotak A/c X1234 by IMPS. Avl bal Rs 22,000"))
    }

    @Test
    fun `pure balance alerts are still dropped, including a balance marked Cr`() {
        val alerts = listOf(
            "AX-HDFCBK-S" to "Available Balance in A/c XX1234 is Rs.45,000.00 as on 16-10-26.",
            "JD-CBSSBI-S" to "Avl Bal in your SBI A/c XX5678 is Rs 8,800.00 Cr as on 16Oct26. -SBI",
            "VK-ICICIB-S" to "Avl Bal for ICICI Bank Acct XX234 is INR 9,751.00 Dr on 16-Oct-26",
            "AX-AXISBK-S" to "Avl bal on your Axis Bank Credit Card XX2468 is INR 45,000.00. Avl Limit INR 60,000.00",
            "BZ-KOTAKB-S" to "Your Kotak A/c X1234 Avl. Bal is Rs 13,000.00 as on 16-10-26"
        )
        for ((sender, text) in alerts) assertEquals("balance alert", reason(sender, text), text)
    }

    // ── AOS-215: direction follows Dr/Cr and the account-anchored phrase ──

    @Test
    fun `a Dr alert that names the payee as credited is a debit`() {
        val t = txn("AD-YESBNK-S", "A/c XX1357 Dr. Rs 500.00 on 16-10-26 and credited to VPA swiggy@icici. UPI Ref 123456789012")
        assertTxn(IngestDirection.DEBIT, "500.00", t)
        assertEquals("swiggy@icici", t.upiVpa); assertEquals(PaymentMethod.UPI, t.paymentMethod)
        assertTxn(IngestDirection.DEBIT, "500.00",
            txn("AD-YESBNK-S", "Dr. Rs 500.00 from your account on 16-10-26, credited to VPA swiggy@icici (UPI Ref 123456789012)"))
    }

    @Test
    fun `the account-anchored phrase wins over a payee credited word`() {
        assertTxn(IngestDirection.DEBIT, "249.00",
            txn("AX-HDFCBK-S", "Rs.249.00 debited from HDFC Bank A/c XX1234 on 16-10-26 and credited to VPA swiggy@icici"))
        assertTxn(IngestDirection.DEBIT, "800", txn("AX-AXISBK-S", "INR 800 debited from your Axis Bank account XX2468; ZOMATO credited. Ref 123456789012"))
        assertTxn(IngestDirection.CREDIT, "1200",
            txn("BZ-KOTAKB-S", "Rs 1,200 credited to your Kotak Bank A/c X1234 from FRIEND who paid via UPI"))
    }

    @Test
    fun `a Cr marker makes it a credit even when a debit word appears`() {
        assertTxn(IngestDirection.CREDIT, "750.00", txn("AD-YESBNK-S", "INR 750.00 Cr to A/c XX1357 on 16-10-26 for the order you paid earlier. Ref 123456789012"))
        assertTxn(IngestDirection.CREDIT, "3000", txn("JD-CBSSBI-S", "Rs.3,000 Cr. on 16Oct26 by transfer sent from ACME. -SBI"))
    }

    @Test
    fun `conflicting strong signals need review and are never guessed`() {
        val conflicts = listOf(
            "AD-YESBNK-S" to "A/c XX1357 credited with Rs 500.00 Dr on 16-10-26",
            "JD-CBSSBI-S" to "Rs 5,000 debited from A/c XX5678 and credited to A/c XX9999 by IMPS. -SBI",
            "AX-HDFCBK-S" to "Rs.300 Dr. Rs.300 Cr. on 16-10-26 for txn ref 123456789012"
        )
        for ((sender, text) in conflicts) assertEquals(SmsParseResult.DIRECTION_UNCLEAR, reason(sender, text), text)
    }

    @Test
    fun `conflicting verbs with no stronger signal need review`() {
        assertEquals(SmsParseResult.DIRECTION_UNCLEAR, reason("AX-HDFCBK-S", "Refund of Rs 249 for the order you paid at AMAZON is processed"))
    }

    @Test
    fun `a conflict with no amount is no transaction at all`() {
        assertEquals("not a recognised transaction message", reason("AX-HDFCBK-S", "Money debited or credited? Check the app"))
    }

    @Test
    fun `Dr and Cr inside words or names are not markers`() {
        assertTxn(IngestDirection.DEBIT, "600", txn("AX-HDFCBK-S", "Rs 600 paid to Dr. Mehta Clinic from HDFC Bank A/c XX1234 via UPI"))
        assertEquals("not a recognised transaction message", reason("AX-HDFCBK-S", "Visit our Credit Desk or Drive-in branch for Rs 0 fees"))
    }

    @Test
    fun `the generic parser direction is unclear only when the signals really disagree`() {
        assertIs<SmsReading.DirectionUnclear>(SmsParserRegistry.GENERIC.read("Rs 100 debited from A/c XX1234 and credited to A/c XX5678"))
        assertIs<SmsReading.Parsed>(SmsParserRegistry.GENERIC.read("Rs 100 debited from A/c XX1234"))
        assertIs<SmsReading.NotATransaction>(SmsParserRegistry.GENERIC.read("Your e-statement is ready"))
        assertNull(SmsParserRegistry.GENERIC.parse("Rs 100 debited from A/c XX1234 and credited to A/c XX5678"))
    }

    @Test
    fun `signals read the direction from each marker form`() {
        fun d(text: String) = SmsSignals.direction(text)
        assertEquals(SmsDirection.Clear(IngestDirection.DEBIT), d("Rs.500.00 Dr"))
        assertEquals(SmsDirection.Clear(IngestDirection.CREDIT), d("Cr. with INR 10"))
        assertEquals(SmsDirection.Clear(IngestDirection.DEBIT), d("a/c no. XXXX5678 has been Dr. for 10"))
        assertEquals(SmsDirection.Clear(IngestDirection.CREDIT), d("credited into your savings account"))
        assertEquals(SmsDirection.None, d("Avl Bal Rs 4,500.00 Cr"))
        assertEquals(SmsDirection.None, d("Your Credit Card and Debit Card statements are ready"))
        assertEquals(SmsDirection.Conflicting, d("Rs 10 debited and Rs 10 received"))
    }

    // ── Hostile input ──

    @Test
    fun `long and repetitive hostile text is read in bounded time`() {
        val hostile = "Avl Bal " + "Rs 1, ".repeat(400) + "Cr and Dr. ".repeat(200) + "will be credited ".repeat(100)
        val started = System.nanoTime()
        parse("AX-HDFCBK-S", hostile.take(2_000))
        parse("AX-HDFCBK-S", ("A/c XX1234 " + "and ".repeat(500)).take(2_000))
        assert((System.nanoTime() - started) / 1_000_000 < 2_000) { "parsing took too long" }
    }
}
