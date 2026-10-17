package dev.adminos.api.domain.ingestion.fallback

import dev.adminos.api.domain.ingestion.events.IngestDirection
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageGroundingTest {
    private fun amounts(text: String) = MessageGrounding.amounts(text).map { it.amount.toPlainString() to it.isBalance }

    @Test
    fun `money figures are read before or after the number with Indian grouping`() {
        assertEquals(listOf("249" to false), amounts("Card charged Rs.249.00 at X"))
        assertEquals(listOf("125000.5" to false), amounts("INR 1,25,000.50 Dr. from A/c"))
        assertEquals(listOf("640" to false), amounts("₹640 paid"))
        assertEquals(listOf("1499" to false), amounts("1,499.00 INR debited"))
        assertEquals(listOf("99" to false), amounts("debited 99 rupees"))
        assertEquals(emptyList(), amounts("Ref 123456 on 16-10-26, 999999 approved"))
    }

    @Test
    fun `balance and limit figures are marked, as whole words only`() {
        assertEquals(listOf("500" to false, "12000" to true), amounts("Rs 500 Dr. Avl Bal Rs 12,000.00"))
        assertEquals(listOf("45000" to true), amounts("Avl Lmt Rs 45,000"))
        assertEquals(listOf("5432" to true, "543" to true), amounts("Total Amt Due Rs 5,432.00, Min Amt Due Rs 543.00"))
        assertEquals(listOf("300" to false), amounts("Paid to GLOBAL STORES Rs 300"), "GLOBAL is not 'bal'")
    }

    @Test
    fun `dates are read in the formats Indian banks write`() {
        val d = LocalDate.of(2026, 10, 16)
        listOf("16-10-26", "16/10/2026", "16.10.26", "16-Oct-26", "16 Oct 2026", "16Oct2026", "2026-10-16", "Oct 16, 2026", "16th October 2026", "16-OCT-2026")
            .forEach { assertEquals(setOf(d), MessageGrounding.dates("on $it at"), it) }
        assertEquals(setOf(LocalDate.of(2026, 9, 3)), MessageGrounding.dates("on 03 Sept 2026"))
    }

    @Test
    fun `impossible or partial dates are not dates`() {
        assertEquals(emptySet(), MessageGrounding.dates("on 31/02/2026, at 10:15, on 16 Oct, ref 2026"))
        assertEquals(emptySet(), MessageGrounding.dates("on 99-99-99"))
    }

    @Test
    fun `last four digits come only from a masked or labelled account or card`() {
        assertEquals(setOf("1234"), MessageGrounding.lastFours("A/c XX1234 debited"))
        assertEquals(setOf("4321"), MessageGrounding.lastFours("your card ending 4321 at"))
        assertEquals(setOf("4321"), MessageGrounding.lastFours("card ending with 4321"))
        assertEquals(setOf("9876"), MessageGrounding.lastFours("Acct no. XXXX9876"))
        assertEquals(setOf("5555"), MessageGrounding.lastFours("Card *5555 used"))
        assertEquals(setOf("2468"), MessageGrounding.lastFours("account 2468 credited"))
        assertEquals(emptySet(), MessageGrounding.lastFours("Rs 2026 on 16-10-2026 ref 1234"))
        assertEquals(emptySet(), MessageGrounding.lastFours("A/c XX123456"), "not the end of a longer number")
    }

    @Test
    fun `direction follows D-43 with the account anchor winning`() {
        assertEquals(IngestDirection.DEBIT, MessageGrounding.direction("Rs 500 debited from your a/c XX1234, credited to VPA x"))
        assertEquals(IngestDirection.CREDIT, MessageGrounding.direction("Rs 500 credited to account XX1234 from X, debited at sender"))
        assertEquals(IngestDirection.DEBIT, MessageGrounding.direction("Card charged Rs 500"))
        assertEquals(IngestDirection.CREDIT, MessageGrounding.direction("Cashback of Rs 50 added"))
    }

    @Test
    fun `sms abbreviations count but credit card and credit limit do not`() {
        assertEquals(IngestDirection.DEBIT, MessageGrounding.direction("INR 500 Dr. from A/c"))
        assertEquals(IngestDirection.CREDIT, MessageGrounding.direction("Rs 500 Cr. to A/c"))
        assertEquals(IngestDirection.DEBIT, MessageGrounding.direction("a debit of INR 500 on your credit card"))
        assertEquals(IngestDirection.CREDIT, MessageGrounding.direction("credit of INR 500 from X"))
        assertNull(MessageGrounding.direction("Payment towards your Credit Card processed, credit limit Rs 1 lakh, debit card XX1"))
    }

    @Test
    fun `neither or both families is unclear, never guessed`() {
        assertNull(MessageGrounding.direction("Txn of Rs 500 done at X"))
        assertNull(MessageGrounding.direction("Rs 500 Dr. and Rs 50 cashback"))
    }

    @Test
    fun `only messages with a money word are worth asking about`() {
        listOf("Rs 50 off", "INR 5", "₹5", "you paid", "amount received", "card charged").forEach { assertTrue(MessageGrounding.mentionsMoney(it), it) }
        listOf("Your KYC is updated", "Branch closed on Sunday", "Rupay card dispatched").forEach { assertFalse(MessageGrounding.mentionsMoney(it), it) }
    }

    @Test
    fun `a zero amount is not a money figure`() {
        assertEquals(emptyList(), MessageGrounding.amounts("Rs 0.00 charged"))
        assertEquals(BigDecimal("1"), MessageGrounding.amounts("Rs 1").single().amount)
    }
}
