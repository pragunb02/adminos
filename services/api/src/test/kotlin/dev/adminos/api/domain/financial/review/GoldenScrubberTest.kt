package dev.adminos.api.domain.financial.review

import dev.adminos.api.domain.financial.PaymentMethod
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.TransactionNature
import dev.adminos.api.domain.financial.TransactionType
import dev.adminos.api.domain.financial.evaluation.GoldenSet
import dev.adminos.api.domain.financial.learning.LearningFixtures.NOW
import dev.adminos.api.domain.financial.learning.LearningFixtures.txn
import dev.adminos.api.domain.ingestion.pipeline.MerchantNormalizer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoldenScrubberTest {
    private val me = UUID.randomUUID()

    private fun scrubbed(t: dev.adminos.api.domain.financial.Transaction, c: TransactionCategory = TransactionCategory.HEALTH) =
        assertIs<ScrubResult.Scrubbed>(GoldenScrubber.scrub(t, c, NOW)).candidate

    @Test
    fun `a UPI shop keeps only its cleaned name in a synthetic narration`() {
        val t = txn(me, "Sai Medicos 2", vpa = "saimedicos2@okaxis").copy(merchantRaw = "UPI-SAI MEDICOS 2-saimedicos2@okaxis-UTIB0000123-428911100099-MEDICINE")
        val c = scrubbed(t)
        assertEquals("UPI-SAI MEDICOS-PAYMENT", c.merchantRaw)
        assertEquals(PaymentMethod.UPI, c.paymentMethod)
        assertEquals(TransactionType.DEBIT, c.direction)
        assertTrue(c.csvLine().startsWith("L") && c.csvLine().endsWith(",UPI-SAI MEDICOS-PAYMENT,,UPI,DEBIT,HEALTH,,real-shaped local"))
        assertTrue("428911" !in c.csvLine() && "@" !in c.csvLine() && "UTIB" !in c.csvLine(), "no reference, UPI ID or IFSC")
    }

    @Test
    fun `cards and other methods get their own narration shape, and a refund is a credit`() {
        assertEquals("POS 400000XXXXXX0000 FORTIS HEALTHCARE", scrubbed(txn(me, "Fortis Healthcare").copy(paymentMethod = PaymentMethod.CARD_CREDIT)).merchantRaw)
        assertEquals("FORTIS HEALTHCARE", scrubbed(txn(me, "Fortis Healthcare").copy(paymentMethod = null)).merchantRaw)
        val refund = txn(me, "Fortis Healthcare").copy(type = TransactionType.REFUND, nature = TransactionNature.REFUND, paymentMethod = PaymentMethod.NETBANKING)
        val c = scrubbed(refund)
        assertEquals(TransactionType.CREDIT, c.direction)
        assertEquals("FORTIS HEALTHCARE", c.merchantRaw)
    }

    @Test
    fun `payments that look like ones to a person are refused`() {
        assertEquals(ScrubResult.Refused(ScrubRefusal.LOOKS_LIKE_A_PERSON), GoldenScrubber.scrub(txn(me, "Ravi Kumar"), TransactionCategory.OTHER, NOW))
        assertEquals(ScrubResult.Refused(ScrubRefusal.LOOKS_LIKE_A_PERSON),
            GoldenScrubber.scrub(txn(me, "Ravi Kumar", vpa = "9800000012@ybl"), TransactionCategory.FOOD, NOW))
    }

    @Test
    fun `transfers, income and nameless payees are refused`() {
        assertEquals(ScrubResult.Refused(ScrubRefusal.NOT_SPENDING), GoldenScrubber.scrub(txn(me, "Own HDFC"), TransactionCategory.TRANSFER, NOW))
        assertEquals(ScrubResult.Refused(ScrubRefusal.NOT_SPENDING), GoldenScrubber.scrub(txn(me, "Acme", type = TransactionType.CREDIT), TransactionCategory.FOOD, NOW))
        assertEquals(ScrubResult.Refused(ScrubRefusal.NO_NAME), GoldenScrubber.scrub(txn(me, null, vpa = "shop@ybl"), TransactionCategory.FOOD, NOW))
        assertEquals(ScrubResult.Refused(ScrubRefusal.NO_NAME), GoldenScrubber.scrub(txn(me, "7 11"), TransactionCategory.FOOD, NOW))
    }

    @Test
    fun `names lose digits, punctuation and commas, and are cut to 60 characters`() {
        assertEquals("A&B CAFE O'BRIEN", GoldenScrubber.cleanName("A&B Cafe-9876543210, O'Brien!!"))
        assertEquals(60, GoldenScrubber.cleanName("X".repeat(80))?.length)
        assertNull(GoldenScrubber.cleanName(null))
        assertNull(GoldenScrubber.cleanName("1"))
    }

    @Test
    fun `a scrubbed row is a valid golden row the pipeline cleans back to the same name`() {
        val c = scrubbed(txn(me, "Sai Medicos", vpa = "saimed@ybl"))
        val header = "id,merchant_raw,upi_vpa,payment_method,direction,expected_category,expected_subcategory,tags"
        val case = GoldenSet.parse("$header\n${c.csvLine()}").single()
        assertEquals(TransactionCategory.HEALTH, case.expected.category)
        assertEquals(setOf("real-shaped", "local"), case.tags)
        assertEquals("Sai Medicos", MerchantNormalizer().normalize(case.input.merchantRaw, case.input.upiVpa))
    }

    @Test
    fun `a candidate validates itself`() {
        assertFailsWith<IllegalArgumentException> { GoldenCandidate(userId = me, merchantRaw = "A,B", paymentMethod = null, direction = TransactionType.DEBIT, category = TransactionCategory.FOOD, createdAt = NOW) }
        assertFailsWith<IllegalArgumentException> { GoldenCandidate(userId = me, merchantRaw = "AB", paymentMethod = null, direction = TransactionType.REFUND, category = TransactionCategory.FOOD, createdAt = NOW) }
        assertFailsWith<IllegalArgumentException> { GoldenCandidate(userId = me, merchantRaw = "AB", paymentMethod = null, direction = TransactionType.DEBIT, category = TransactionCategory.TRANSFER, createdAt = NOW) }
        assertFailsWith<IllegalArgumentException> { GoldenCandidate(userId = me, merchantRaw = " ", paymentMethod = null, direction = TransactionType.DEBIT, category = TransactionCategory.FOOD, createdAt = NOW) }
    }
}
