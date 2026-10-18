package dev.adminos.api.domain.agent.evaluation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AOS-713 (D-61 style): unusual-charge alerts may not get worse on the synthetic streams. Raising the
 * floor is deliberate: `./gradlew -q anomalyReport -PwriteBaseline`, committed with the change.
 */
class AnomalyRegressionTest {
    private val rows get() = SHARED.rows
    private val reports get() = SHARED.reports

    private companion object {
        /** Scored once for the class: each replay of the year of streams takes a few seconds. */
        val SHARED by lazy { Shared() }
    }

    private class Shared {
        val rows = SpendingStreams.load()
        val reports = anomalyReports(rows).associateBy { it.variant }
    }

    @Test
    fun `no variant scores below its committed baseline`() {
        val floors = AnomalyBaselines.load()
        assertEquals(AnomalyEvaluator.VARIANTS.map { it.name }.toSet(), floors.map { it.variant }.toSet(), "a variant without a baseline: re-record it")
        val drops = floors.flatMap { floor ->
            val report = reports.getValue(floor.variant)
            assertEquals(floor.rows, report.rows, "The streams changed size: re-record with ./gradlew -q anomalyReport -PwriteBaseline")
            floor.regressions(report).map { "${floor.variant}: $it" }
        }
        if (drops.isNotEmpty()) fail("Alerts got worse on the synthetic streams:\n" + drops.joinToString("\n") { "  - $it" } +
            "\nRun ./gradlew -q anomalyReport to see the false alerts and misses.")
    }

    @Test
    fun `the new detector beats the old one and meets the research target`() {
        val old = reports.getValue(AnomalyEvaluator.OLD.name)
        val new = reports.getValue(AnomalyEvaluator.NEW.name)
        assertTrue(new.precision > old.precision, "precision ${new.precision} vs ${old.precision}")
        assertTrue(new.recall >= old.recall, "recall ${new.recall} vs ${old.recall}")
        assertTrue(new.falseAlertsPerMonth < old.falseAlertsPerMonth / 2, "false alerts ${new.falseAlertsPerMonth} vs ${old.falseAlertsPerMonth}")
        assertTrue(new.quietStreamAlertsPerMonth <= 2.0, "research target: at most 2 alerts a month on a normal stream")
    }

    @Test
    fun `the committed streams are what the seeded generator makes`() {
        assertEquals(SpendingStreams.generate(), rows, "streams.csv is stale: regenerate with ./gradlew -q anomalyReport -PregenerateStreams")
    }

    @Test
    fun `the streams cover every kind of anomaly and of normal variation`() {
        val labels = rows.groupingBy { it.label }.eachCount()
        SpendLabel.entries.forEach { assertTrue((labels[it] ?: 0) >= 4, "fewer than 4 '${it.code}' rows") }
        val tags = rows.map { it.tag }.toSet()
        assertTrue(tags.containsAll(setOf("festival", "yearly", "dining", "travel", "travel_foreign", "fixed", "regular")), "$tags")
        assertEquals(2, rows.map { it.stream }.distinct().count { s -> rows.none { it.stream == s && it.label.injected } }, "two quiet streams")
    }

    @Test
    fun `a baseline validates itself and names each drop`() {
        val floor = AnomalyBaseline("v", 10, precision = 0.5, recall = 0.5, falseAlertsPerMonth = 1.0)
        val report = reports.getValue(AnomalyEvaluator.OLD.name).copy(precision = 0.4, recall = 0.6, falseAlertsPerMonth = 1.5)
        assertEquals(listOf("precision dropped from 0.5 to 0.4", "false alerts per month rose from 1.0 to 1.5"), floor.regressions(report))
        assertFailsWith<IllegalArgumentException> { floor.copy(variant = " ") }
        assertFailsWith<IllegalArgumentException> { floor.copy(precision = 1.5) }
        assertFailsWith<IllegalArgumentException> { floor.copy(falseAlertsPerMonth = -1.0) }
        assertTrue(formatReport(report).contains("recall by kind"))
    }

    @Test
    fun `a malformed row or label is refused`() {
        assertFailsWith<IllegalArgumentException> { SpendLabel.fromCode("weird") }
        assertFailsWith<IllegalArgumentException> { rows.first().copy(amount = java.math.BigDecimal.ZERO) }
        assertFailsWith<IllegalArgumentException> { rows.first().copy(merchant = "A, B") }
        assertFailsWith<IllegalStateException> { SpendingStreams.load("/anomalies/missing.csv") }
    }
}
