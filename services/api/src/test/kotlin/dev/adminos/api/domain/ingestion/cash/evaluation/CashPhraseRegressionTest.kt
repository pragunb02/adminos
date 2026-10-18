package dev.adminos.api.domain.ingestion.cash.evaluation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AOS-718 (D-61 style): the shipped cash-phrase rules may not score below the checked-in baseline on the synthetic
 * phrase set. Raising it is deliberate: `./gradlew -q cashPhraseReport -PwriteBaseline`, with the change that moved it.
 */
class CashPhraseRegressionTest {
    private val set = PhraseSet.load()

    @Test
    fun `the rules score no lower than the baseline on the phrase set`() {
        val report = PhraseEvaluator.evaluate(set)
        val baseline = PhraseBaseline.load()
        assertEquals(baseline.phrases, report.phrases, "The phrase set changed size: re-record with ./gradlew -q cashPhraseReport -PwriteBaseline")
        val drops = listOf(
            "exact" to (baseline.exact to report.exact),
            "entry precision" to (baseline.entryPrecision to report.entryPrecision),
            "entry recall" to (baseline.entryRecall to report.entryRecall),
            "category accuracy" to (baseline.categoryAccuracy to report.categoryAccuracy),
            "refusals" to (baseline.refusals to report.refusals)
        ).filter { (_, v) -> v.second < v.first - 1e-9 }
        if (drops.isNotEmpty()) {
            fail("Cash phrase parsing got worse:\n" + drops.joinToString("\n") { (k, v) -> "  - $k dropped from ${v.first} to ${v.second}" } +
                "\nMisses:\n" + report.misses.joinToString("\n") { "  ${it.id} \"${it.text}\": want [${it.expected}] got [${it.got}]" })
        }
    }

    @Test
    fun `the rules beat reading every number as an entry`() {
        val rules = PhraseEvaluator.evaluate(set)
        val naive = PhraseEvaluator.evaluate(set, NaiveNumbersParser)
        assertTrue(rules.exact > naive.exact && rules.entryPrecision > naive.entryPrecision && rules.entryRecall > naive.entryRecall)
    }

    @Test
    fun `the set is synthetic, large enough, and covers the hard cases`() {
        assertTrue(set.size >= 150, "${set.size} phrases")
        val header = PhraseSet::class.java.getResource(PhraseSet.RESOURCE)!!.readText().lineSequence().first()
        assertTrue("SYNTHETIC" in header)
        val tags = set.flatMap { it.tags }.groupingBy { it }.eachCount()
        for (tag in listOf("heldout", "hinglish", "quantity", "future", "leap", "catalogue", "k", "time", "no-amount", "shared-date", "weekday")) {
            assertTrue((tags[tag] ?: 0) >= 2, "fewer than 2 '$tag' phrases")
        }
        assertTrue((tags["heldout"] ?: 0) >= 30, "held-out phrases: ${tags["heldout"]}")
    }

    @Test
    fun `malformed phrase lines fail with their line number`() {
        val header = "id\ttoday\ttext\texpected\tunparsed\ttags"
        assertTrue(assertFailsWith<IllegalArgumentException> { PhraseSet.parse("$header\nX1\t\tchai 40\t40 nosuch T\t\t") }.message!!.contains("line 2 (X1)"))
        assertFailsWith<IllegalArgumentException> { PhraseSet.parse("$header\nX1\t\tchai 40\t40 food\t\t") }
        assertFailsWith<IllegalArgumentException> { PhraseSet.parse("$header\nX1\t\tchai") }
        assertFailsWith<IllegalArgumentException> { PhraseSet.parse("$header\nX1\t\ta\t\t\t\nX1\t\tb\t\t\t") }
        assertFailsWith<IllegalArgumentException> { PhraseSet.parse("id,text\nX1,chai") }
        assertFailsWith<IllegalStateException> { PhraseSet.load("/cash/missing.tsv") }
        assertFailsWith<IllegalStateException> { PhraseBaseline.load("/cash/missing.json") }
        val ok = PhraseSet.parse("# c\n$header\nX1\t2026-01-05\tchai 40\\nauto 50\t40 food T; 50 transport T-1\tno_amount\ta b").single()
        assertEquals("chai 40\nauto 50", ok.text)
        assertEquals(java.time.LocalDate.of(2026, 1, 4), ok.entries[1].date)
        assertEquals(setOf("a", "b"), ok.tags)
    }
}
