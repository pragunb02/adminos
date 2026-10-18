package dev.adminos.api.domain.financial.splits.eval

import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AOS-911: the Splitwise matcher on its synthetic eval set never falls below the committed baseline. */
class SplitEvalTest {
    private val baseline = evalJson.decodeFromString(SplitBaseline.serializer(),
        checkNotNull(javaClass.getResource("/splits/baseline.json")) { "missing src/test/resources/splits/baseline.json" }.readText())

    @Test
    fun `the eval set meets the committed floor`() = runBlocking<Unit> {
        val report = SplitEvaluator.run(baseline.seed)
        assertEquals(emptyList(), baseline.regressions(report), evalJson.encodeToString(SplitEvalReport.serializer(), report))
    }

    @Test
    fun `importing Splitwise brings the console closer to the truth, and Review closes the rest of the gap it can`() = runBlocking<Unit> {
        val r = SplitEvaluator.run()
        assertTrue(BigDecimal(r.errorAfterImport) < BigDecimal(r.errorWithoutSplitwise), r.toString())
        assertTrue(BigDecimal(r.errorAfterReview) <= BigDecimal(r.errorAfterImport), r.toString())
        assertTrue(BigDecimal(r.receivedAfterImport) < BigDecimal(r.receivedWithoutSplitwise), "paybacks stop counting as income")
        assertEquals(0, r.appliedWrong, "a wrong match changes a total silently: none are allowed")
    }

    @Test
    fun `across 30 other seeds no wrong match is ever applied`() = runBlocking<Unit> {
        val reports = (1..30).map { SplitEvaluator.run(it) }
        val wrong = reports.filter { it.appliedWrong > 0 || it.falseMatches > 0 }.map { "seed ${it.seed}: ${it.appliedWrong} wrong, ${it.falseMatches} false" }
        assertEquals(emptyList(), wrong)
        println("AOS-911 eval over seeds 1..30: auto recall %.4f, review recall %.4f (means)".format(
            reports.map { it.autoRecall }.average(), reports.map { it.reviewRecall }.average()))
        assertTrue(reports.all { BigDecimal(it.errorAfterImport) < BigDecimal(it.errorWithoutSplitwise) })
    }

    @Test
    fun `the set is the same on every run`() = runBlocking<Unit> {
        assertEquals(SplitEvaluator.run(), SplitEvaluator.run())
    }

    @Test
    fun `a regression is reported in words`() {
        val r = runBlocking { SplitEvaluator.run() }
        val worse = r.copy(autoPrecision = 0.0, autoRecall = 0.0, reviewRecall = 0.0, appliedWrong = 99, falseMatches = 99,
            friendPaidSharesCorrect = -1, errorAfterImport = "999999.00", errorAfterReview = "999999.00")
        assertEquals(8, SplitBaseline.of(r).regressions(worse).size)
    }
}
