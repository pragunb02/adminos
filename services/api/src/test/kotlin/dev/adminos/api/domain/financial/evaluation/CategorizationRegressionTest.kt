package dev.adminos.api.domain.financial.evaluation

import dev.adminos.api.domain.financial.TransactionCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AOS-511, D-61: the shipped categorizer may not score below the checked-in baseline on the golden set.
 * Raising the numbers is deliberate: `./gradlew -q categorizationReport -PwriteBaseline`, then commit
 * baseline.json with the change that moved them.
 */
class CategorizationRegressionTest {

    private val golden = GoldenSet.load()

    @Test
    fun `the categorizer scores no lower than the baseline on the golden set`() {
        val report = rulesReport()
        val baseline = Baselines.load(Baselines.RULES)
        assertEquals(baseline.goldenRows, report.total,
            "The golden set changed size: re-record the baseline with ./gradlew -q categorizationReport -PwriteBaseline")
        val drops = RegressionGate.regressions(report, baseline)
        if (drops.isNotEmpty()) {
            fail("Categorization got worse on the golden set:\n" + drops.joinToString("\n") { "  - $it" } +
                "\nRun ./gradlew -q categorizationReport to see the misses. If the drop is intended, re-record the baseline " +
                "with -PwriteBaseline and say why in the ticket.")
        }
        RegressionGate.improvements(report, baseline).forEach { println("categorization improved: $it (record with -PwriteBaseline)") }
    }

    @Test
    fun `the golden set has over 200 synthetic rows covering every category`() {
        assertTrue(golden.size >= 200, "golden set has ${golden.size} rows")
        val missing = TransactionCategory.entries.toSet() - golden.map { it.expected.category }.toSet()
        assertEquals(emptySet(), missing)
        val header = GoldenSet::class.java.getResource(GoldenSet.RESOURCE)!!.readText().lineSequence().first()
        assertTrue(header.contains("SYNTHETIC"), "the golden set must say it is synthetic")
    }

    @Test
    fun `the golden set includes the hard cases`() {
        val tags = golden.flatMap { it.tags }.groupingBy { it }.eachCount()
        for (tag in listOf("p2p", "gateway", "amazon", "fuel", "nach", "rent", "refund", "atm", "cardbill", "wholeword", "brand-in-vpa")) {
            assertTrue((tags[tag] ?: 0) >= 3, "fewer than 3 '$tag' rows")
        }
    }

    @Test
    fun `the golden set holds no real-looking phone numbers in UPI IDs`() {
        // Synthetic mobile-number handles use the 98000000xx and 88000000xx ranges only.
        val phoneHandle = Regex("(?<![0-9])[6-9][0-9]{9}@")
        val offenders = golden.flatMap { c -> listOfNotNull(c.input.merchantRaw, c.input.upiVpa).flatMap { t -> phoneHandle.findAll(t).map { c.id to it.value } } }
            .filterNot { (_, v) -> v.startsWith("98000000") || v.startsWith("88000000") }
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun `malformed golden rows fail with their line number`() {
        val header = "id,merchant_raw,upi_vpa,payment_method,direction,expected_category,expected_subcategory,tags"
        val e = assertFailsWith<IllegalArgumentException> { GoldenSet.parse("$header\nA1,X,,UPI,DEBIT,RENT,,") }
        assertTrue(e.message!!.contains("line 2 (A1)"))
        assertFailsWith<IllegalArgumentException> { GoldenSet.parse("$header\nA1,X,,UPI,DEBIT,FOOD") }
        assertFailsWith<IllegalArgumentException> { GoldenSet.parse("$header\nA1,X,,UPI,DEBIT,FOOD,,\nA1,Y,,UPI,DEBIT,FOOD,,") }
        assertFailsWith<IllegalArgumentException> { GoldenSet.parse("id,raw\nA1,X") }
        assertFailsWith<IllegalStateException> { GoldenSet.load("/categorization/missing.csv") }
        assertFailsWith<IllegalStateException> { Baselines.load("/categorization/missing.json") }
    }

    @Test
    fun `comments and blank lines are skipped and blanks become absent values`() {
        val header = "id,merchant_raw,upi_vpa,payment_method,direction,expected_category,expected_subcategory,tags"
        val cases = GoldenSet.parse("# synthetic\n\n$header\nA1,,a@ybl,,CREDIT,OTHER,,p2p ambiguous\n")
        val a = cases.single()
        assertEquals(null, a.input.merchantRaw); assertEquals(null, a.input.paymentMethod); assertEquals(null, a.expected.subcategory)
        assertEquals(setOf("p2p", "ambiguous"), a.tags)
    }
}
