package dev.adminos.api.domain.financial.grouping

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AOS-709 (D-61 style): merchant grouping may not get worse on the synthetic pairs, and the shipped
 * matcher keeps the research's precision floor (0.98: a wrong merge hides a real duplicate).
 * Raising the floor is deliberate: `./gradlew -q merchantGroupingReport -PwriteBaseline`.
 */
class MerchantGroupingRegressionTest {
    private val pairs = MerchantPairs.load()
    private val scores = GroupingEvaluation.scores(pairs).associateBy { it.decider }

    @Test
    fun `no decider scores below its committed baseline`() {
        val floors = GroupingEvaluation.baselines()
        assertEquals(GroupingEvaluation.DECIDERS.map { it.name }.toSet(), floors.map { it.decider }.toSet())
        val drops = floors.flatMap { f ->
            val s = scores.getValue(f.decider)
            assertEquals(f.pairs, s.pairs, "the pair set changed: re-record with -PwriteBaseline")
            f.regressions(s).map { "${f.decider}: $it" }
        }
        if (drops.isNotEmpty()) fail("Merchant grouping got worse:\n" + drops.joinToString("\n") { "  - $it" })
    }

    @Test
    fun `the shipped matcher keeps precision at 0_98 or more and beats exact names on recall`() {
        val shipped = scores.getValue(GroupingEvaluation.SHIPPED.name)
        val exact = scores.getValue("exact cleaned name only")
        assertTrue(shipped.precision >= 0.98, "precision ${shipped.precision}: false merges ${shipped.falsePositiveIds}")
        assertTrue(shipped.recall > exact.recall, "recall ${shipped.recall} vs exact ${exact.recall}")
    }

    @Test
    fun `the pair set is synthetic, large enough and covers the hard cases`() {
        assertTrue(pairs.size >= 150, "${pairs.size} pairs")
        assertTrue(pairs.count { it.same } >= 60 && pairs.count { !it.same } >= 60)
        val tags = pairs.groupingBy { it.tag }.eachCount()
        for (tag in listOf("suffix", "legal", "gateway", "spelling", "vpa", "person", "lookalike", "distinct-word", "brand-veto", "gateway-vpa")) {
            assertTrue((tags[tag] ?: 0) >= 1, "no '$tag' pair")
        }
        assertEquals(pairs.size, pairs.map { it.id }.toSet().size, "ids are unique")
        val phone = Regex("(?<![0-9])[6-9][0-9]{9}@")
        assertEquals(emptyList(), pairs.flatMap { listOfNotNull(it.upiA, it.upiB) }.filter { phone.containsMatchIn(it) && !it.startsWith("98000001") })
    }

    @Test
    fun `a malformed pair set or baseline is refused`() {
        assertFailsWith<IllegalStateException> { MerchantPairs.load("/merchants/missing.csv") }
        assertFailsWith<IllegalArgumentException> { LabelledPair("x", "Same", "same", null, null, true, "t") }
        val floor = PairBaseline("d", 1, 0.9, 0.9)
        val worse = scores.values.first().copy(precision = 0.5, recall = 0.95)
        assertEquals(listOf("precision dropped from 0.9 to 0.5"), floor.regressions(worse))
        assertTrue(GroupingEvaluation.format(worse).contains("precision"))
    }
}
