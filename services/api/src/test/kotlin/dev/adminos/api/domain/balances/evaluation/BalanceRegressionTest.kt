package dev.adminos.api.domain.balances.evaluation

import dev.adminos.api.domain.ingestion.sms.NoBalance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AOS-720 (D-61 style): balance extraction may not score below the checked-in baseline on the synthetic SMS set, and
 * may never give a balance where none must be read (cards, wallets, forgeries). Raising the floor is deliberate:
 * `./gradlew -q balanceReport -PwriteBaseline`, with the change that moved it.
 */
class BalanceRegressionTest {
    private val set = BalanceSet.load()

    @Test
    fun `the rules score no lower than the baseline on the SMS set`() {
        val report = BalanceEvaluator.evaluate(set)
        val baseline = BalanceBaseline.load()
        assertEquals(baseline.cases, report.cases, "The SMS set changed size: re-record with ./gradlew -q balanceReport -PwriteBaseline")
        val drops = listOf(
            "exact" to (baseline.exact to report.exact),
            "precision" to (baseline.precision to report.precision),
            "recall" to (baseline.recall to report.recall),
            "reason accuracy" to (baseline.reasonAccuracy to report.reasonAccuracy)
        ).filter { (_, v) -> v.second < v.first - 1e-9 }
        val worse = if (report.falseBalanceRate > baseline.falseBalanceRate + 1e-9) listOf("false balances rose from ${baseline.falseBalanceRate} to ${report.falseBalanceRate}") else emptyList()
        if (drops.isNotEmpty() || worse.isNotEmpty()) {
            fail("SMS balance extraction got worse:\n" + (drops.map { (k, v) -> "  - $k dropped from ${v.first} to ${v.second}" } + worse.map { "  - $it" }).joinToString("\n") +
                "\nMisses:\n" + report.misses.joinToString("\n") { "  ${it.id}: want [${it.expected}] got [${it.got}]" })
        }
    }

    @Test
    fun `no message that must give no balance gives one`() {
        assertEquals(0.0, BalanceEvaluator.evaluate(set).falseBalanceRate)
    }

    @Test
    fun `the rules beat taking the first figure after any bal`() {
        val rules = BalanceEvaluator.evaluate(set)
        val naive = BalanceEvaluator.evaluate(set, NaiveBalance)
        assertTrue(rules.exact > naive.exact && rules.precision > naive.precision && rules.falseBalanceRate < naive.falseBalanceRate)
    }

    @Test
    fun `the set is synthetic, large enough, and covers every bank and refusal`() {
        assertTrue(set.size >= 120, "${set.size} messages")
        val header = BalanceSet::class.java.getResource(BalanceSet.RESOURCE)!!.readText().lineSequence().first()
        assertTrue("SYNTHETIC" in header)
        val tags = set.flatMap { it.tags }.groupingBy { it }.eachCount()
        for (tag in listOf("hdfc", "sbi", "icici", "axis", "kotak", "generic", "hostile", "credit-card", "overdrawn", "as-on", "masked", "two-accounts", "two-figures")) {
            assertTrue((tags[tag] ?: 0) >= 2, "fewer than 2 '$tag' messages")
        }
        assertTrue((tags["heldout"] ?: 0) >= 40, "held-out messages: ${tags["heldout"]}")
        val reasons = set.mapNotNull { (it.expected as? Expected.None)?.reason }.toSet()
        assertEquals(NoBalance.entries.toSet(), reasons, "every refusal is in the set")
    }

    @Test
    fun `malformed lines fail with their line number`() {
        val header = "id\tsender\ttext\texpected\ttags"
        assertTrue(assertFailsWith<IllegalArgumentException> { BalanceSet.parse("$header\nX1\tAX-HDFCBK-S\tt\t!nosuch\t") }.message!!.contains("line 2 (X1)"))
        assertFailsWith<IllegalArgumentException> { BalanceSet.parse("$header\nX1\tAX-HDFCBK-S\tt\t12.00\t") }
        assertFailsWith<IllegalArgumentException> { BalanceSet.parse("$header\nX1\tAX-HDFCBK-S") }
        assertFailsWith<IllegalArgumentException> { BalanceSet.parse("$header\nX1\ts\tt\t!none\t\nX1\ts\tt\t!none\t") }
        assertFailsWith<IllegalArgumentException> { BalanceSet.parse("id,text\nX1,t") }
        assertFailsWith<IllegalStateException> { BalanceSet.load("/balances/missing.tsv") }
        assertFailsWith<IllegalStateException> { BalanceBaseline.load("/balances/missing.json") }
        val ok = BalanceSet.parse("# c\n$header\nX1\tAX-HDFCBK-S\tAvl Bal Rs 1.00 A/c XX1234\t1.00 1234\ta b").single()
        assertEquals(setOf("a", "b"), ok.tags)
    }

    @Test
    fun `the report runs and writes its json`() {
        val dir = kotlin.io.path.createTempDirectory("balances").toFile()
        main(arrayOf(java.io.File(dir, "r.json").path, "--write-baseline", dir.path))
        assertTrue(java.io.File(dir, "r.json").readText().contains("\"exact\""))
        assertTrue(java.io.File(dir, "baseline.json").readText().contains("\"rules\""))
        assertFailsWith<IllegalArgumentException> { main(emptyArray()) }
        dir.deleteRecursively()
    }
}
