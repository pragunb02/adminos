package dev.adminos.api.domain.ingestion.sms

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.ingestion.events.IngestDirection
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** AOS-909: pay-later providers' SMS. All texts are synthetic. */
class PayLaterSmsParserTest {
    private val on = LocalDate.of(2026, 10, 18)
    private fun parse(text: String) = PayLaterSmsParser.parse(text, on)

    private fun bill(text: String) = assertIs<PayLaterMessage.Bill>(parse(text), text).notice

    @Test
    fun `bills in the usual shapes`() {
        with(bill("Your LazyPay bill of Rs.1,234.50 is due on 03-Nov-2026. Pay now to avoid late fees.")) {
            assertEquals("LazyPay", provider); assertEquals(BigDecimal("1234.50"), amount); assertEquals(LocalDate.of(2026, 11, 3), dueDate); assertNull(minimumDue)
        }
        with(bill("Simpl: Total due Rs 2,345 by 5th Nov. Min due Rs 200.")) {
            assertEquals("Simpl", provider); assertEquals(BigDecimal("2345.00"), amount); assertEquals(BigDecimal("200.00"), minimumDue); assertEquals(LocalDate.of(2026, 11, 5), dueDate)
        }
        with(bill("Amazon Pay Later: your statement of INR 3210.00 is generated. Due date: 05/11/2026")) {
            assertEquals("Amazon Pay Later", provider); assertEquals(LocalDate.of(2026, 11, 5), dueDate)
        }
        assertEquals(LocalDate.of(2026, 11, 3), bill("Flipkart Pay Later bill ₹999 - repay by Nov 3, 2026").dueDate)
        assertEquals(LocalDate.of(2026, 11, 7), bill("PAYTM POSTPAID: AMOUNT DUE RS 500 DUE ON 07-NOV-2026").dueDate)
    }

    @Test
    fun `a due date without a year is the next one from a week before the message`() {
        assertEquals(LocalDate.of(2027, 1, 5), PayLaterSmsParser.dueDate("5 Jan", LocalDate.of(2026, 12, 28)))
        assertEquals(LocalDate.of(2026, 12, 25), PayLaterSmsParser.dueDate("25 Dec", LocalDate.of(2026, 12, 28)), "a recent overdue date stays this year")
        assertNull(PayLaterSmsParser.dueDate("31 Feb 2026", on), "no 31 February")
        assertNull(PayLaterSmsParser.dueDate("5 Nov 2029", on), "more than a year away is a mis-read")
        assertNull(PayLaterSmsParser.dueDate("soon", on))
    }

    @Test
    fun `a purchase is a pay-later spend at the merchant, tagged with the provider`() {
        val p = assertIs<PayLaterMessage.Purchase>(parse("Rs.450 spent at Swiggy using LazyPay. Total due Rs 1,684.")).transaction
        assertEquals(IngestDirection.DEBIT, p.direction)
        assertEquals(BigDecimal("450.00"), p.amount)
        assertEquals("Swiggy", p.merchantRaw)
        assertEquals(PaymentMethod.PAY_LATER, p.paymentMethod)
        assertEquals(mapOf("payLater" to "LazyPay"), p.markers)
        assertEquals("Zomato", assertIs<PayLaterMessage.Purchase>(parse("₹299 paid to Zomato via Simpl")).transaction.merchantRaw)
        assertNull(assertIs<PayLaterMessage.Purchase>(parse("Simpl txn of Rs 120 successful")).transaction.merchantRaw, "no merchant named")
    }

    @Test
    fun `repayment confirmations, offers and unreadable bills are not transactions`() {
        assertEquals(PayLaterMessage.Repayment, parse("Payment of Rs 1,234 received for your LazyPay bill. Thank you!"))
        assertEquals(PayLaterMessage.Repayment, parse("Thank you for your repayment to Simpl."))
        assertIs<PayLaterMessage.Unreadable>(parse("Your LazyPay limit is now Rs 10,000. Enjoy!"))
        assertIs<PayLaterMessage.Unreadable>(parse("Your Simpl bill is ready. Open the app to view."), "no amount")
        assertIs<PayLaterMessage.Unreadable>(parse("Your Simpl bill of Rs 500 is ready."), "no due date")
        assertIs<PayLaterMessage.Unreadable>(parse("Simpl purchase completed"), "a purchase without an amount")
    }

    @Test
    fun `a bank's own alert or a mandate notice is never read as a provider message`() {
        assertNull(parse("Rs 1,234.00 debited from A/c XX1234 to LAZYPAY on 18-10-26. UPI Ref 123456789012"))
        assertNull(parse("UPI AutoPay mandate for Simpl of Rs 5000 created. UMN abc123@upi"))
        assertNull(parse("Rs 500 debited from your account for Swiggy"), "names no provider")
    }

    @Test
    fun `hostile texts fail closed`() {
        assertNull(parse("LazyPay bill Rs 100 due 05-Nov-2026 " + "x".repeat(3000)), "longer than an SMS chain")
        assertIs<PayLaterMessage.Unreadable>(parse("Your LazyPay bill of Rs 99,99,999 is due on 05-Nov-2026"), "over the cap")
        assertIs<PayLaterMessage.Unreadable>(parse("Rs 50,00,000 spent at Swiggy using LazyPay"), "over the cap")
        assertIs<PayLaterMessage.Unreadable>(parse("Your LazyPay bill of Rs \uff14\uff15\uff10 is due on 05-Nov-2026"), "look-alike digits are no amount")
        assertNull(parse("Your Lazy\u200bPay bill of Rs 450 is due on 05-Nov-2026"), "a hidden character breaks the provider's name: not a provider message")
        val minAboveTotal = bill("Simpl bill Rs 100, min due Rs 500, due on 05-Nov-2026")
        assertNull(minAboveTotal.minimumDue, "a minimum above the bill is dropped")
        assertFailsWith<IllegalArgumentException> { PayLaterBillNotice("Simpl", BigDecimal.ZERO, null, on) }
        assertFailsWith<IllegalArgumentException> { PayLaterBillNotice("Simpl", BigDecimal.TEN, BigDecimal("11"), on) }
    }

    @Test
    fun `the registry reads providers before the drop rules, and still drops OTPs and personal numbers`() {
        val reg = SmsParserRegistry()
        val at = Instant.parse("2026-10-18T05:00:00Z")
        assertIs<SmsParseResult.PayLaterBill>(reg.parse(SmsMessage("JM-LZYPAY-S", "Your LazyPay bill of Rs 1,234 is due on 03-Nov-2026", at)))
        val purchase = assertIs<SmsParseResult.Transaction>(reg.parse(SmsMessage("VM-SIMPL", "₹299 paid to Zomato via Simpl", at))).parsed
        assertEquals(PaymentMethod.PAY_LATER, purchase.paymentMethod)
        assertEquals("pay-later repayment confirmation; the bank alert records the payment",
            assertIs<SmsParseResult.Ignored>(reg.parse(SmsMessage("VM-SIMPL", "Thank you for your payment to Simpl", at))).reason)
        assertIs<SmsParseResult.Ignored>(reg.parse(SmsMessage("JM-LZYPAY", "OTP 123456 for your LazyPay bill payment of Rs 100", at)))
        assertIs<SmsParseResult.Ignored>(reg.parse(SmsMessage("+919812345678", "Your LazyPay bill of Rs 1,234 is due on 03-Nov-2026", at)), "a personal number is never a provider")
        assertIs<SmsParseResult.Ignored>(reg.parse(SmsMessage("JM-LZYPAY-P", "Your LazyPay bill of Rs 1,234 is due on 03-Nov-2026", at)), "promotional")
        val bank = assertIs<SmsParseResult.Transaction>(reg.parse(SmsMessage("JM-HDFCBK", "Rs 1234.00 debited from A/c XX1234 to LAZYPAY on 18-10-26", at))).parsed
        assertEquals(null, bank.paymentMethod.takeIf { it == PaymentMethod.PAY_LATER }, "the bank's repayment record is a bank debit")
    }
}
