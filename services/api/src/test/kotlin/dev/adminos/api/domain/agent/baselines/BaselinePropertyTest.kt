package dev.adminos.api.domain.agent.baselines

import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.IntRange
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Provide
import java.math.BigDecimal

/** AOS-713: invariants of the median/MAD baseline over any history. */
class BaselinePropertyTest {
    private val models = listOf(MedianMadBaseline(), MedianMadBaseline(scale = AmountScale.LINEAR))

    @Provide
    fun histories(): Arbitrary<List<Int>> = Arbitraries.integers().between(1, 200_000).list().ofMinSize(5).ofMaxSize(40)

    @Property(tries = 300)
    fun `an amount at or below the median is never unusual`(
        @ForAll("histories") history: List<Int>,
        @ForAll @IntRange(min = 0, max = 3) steps: Int
    ) {
        val amounts = history.map { BigDecimal(it) }
        val median = AmountStats.median(amounts)
        for (m in models) {
            val b = m.fit(amounts) ?: throw AssertionError("5 or more positive amounts always fit")
            assert(!b.isUnusual(median, ToleranceLevel(steps))) { "${m.name}: the median itself was unusual" }
        }
    }

    @Property(tries = 300)
    fun `more tolerance never makes an amount more unusual`(
        @ForAll("histories") history: List<Int>,
        @ForAll @IntRange(min = 1, max = 2_000_000) amount: Int
    ) {
        val x = BigDecimal(amount)
        for (m in models) {
            val b = m.fit(history.map { BigDecimal(it) }) ?: throw AssertionError("fits")
            val verdicts = (0..ToleranceLevel.MAX).map { b.isUnusual(x, ToleranceLevel(it)) }
            assert(verdicts.zipWithNext().all { (lower, higher) -> lower || !higher }) { "${m.name}: $verdicts" }
        }
    }

    @Property(tries = 200)
    fun `on the log scale the verdict doesn't depend on the currency unit`(
        @ForAll("histories") history: List<Int>,
        @ForAll @IntRange(min = 10, max = 1_000_000) amount: Int,
        @ForAll @IntRange(min = 2, max = 100) factor: Int
    ) {
        val model = MedianMadBaseline()
        val plain = model.fit(history.map { BigDecimal(it) }) ?: throw AssertionError("fits")
        val scaled = model.fit(history.map { BigDecimal(it.toLong() * factor) }) ?: throw AssertionError("fits")
        val d1 = plain.distance(BigDecimal(amount))
        val d2 = scaled.distance(BigDecimal(amount.toLong() * factor))
        assert(kotlin.math.abs(d1 - d2) < 1e-6) { "$d1 vs $d2" }
    }
}
