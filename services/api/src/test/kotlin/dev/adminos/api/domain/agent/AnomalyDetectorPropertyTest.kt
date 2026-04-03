package dev.adminos.api.domain.agent

import dev.adminos.api.domain.common.workflow.RuleResult
import net.jqwik.api.*
import net.jqwik.api.constraints.DoubleRange
import net.jqwik.api.constraints.IntRange

/**
 * Property 5: Anomaly confidence score is always between 0.0 and 1.0 inclusive,
 * and multi-rule confidence >= single-rule confidence.
 *
 * **Validates: Requirements 11.1, 11.6**
 */
class AnomalyDetectorPropertyTest {

    @Property(tries = 200)
    fun `confidence score is always between 0 and 1`(
        @ForAll("ruleResults") fired: List<@From("singleRule") RuleResult>
    ) {
        if (fired.isEmpty()) return

        val confidence = AnomalyDetectorService.computeConfidence(fired)
        assert(confidence >= 0.0) { "Confidence $confidence is below 0.0" }
        assert(confidence <= 1.0) { "Confidence $confidence exceeds 1.0" }
    }

    @Property(tries = 200)
    fun `multi-rule confidence is greater than or equal to single-rule confidence`(
        @ForAll("singleRule") singleRule: RuleResult,
        @ForAll("additionalRules") additionalRules: List<@From("singleRule") RuleResult>
    ) {
        val singleFired = listOf(singleRule)
        val multiFired = listOf(singleRule) + additionalRules

        if (multiFired.size <= 1) return

        val singleConf = AnomalyDetectorService.computeConfidence(singleFired)
        val multiConf = AnomalyDetectorService.computeConfidence(multiFired)

        assert(multiConf >= singleConf) {
            "Multi-rule confidence $multiConf < single-rule confidence $singleConf " +
                "(rules: ${multiFired.map { it.ruleName }})"
        }
    }

    @Property(tries = 100)
    fun `confidence with empty rules is zero`() {
        val confidence = AnomalyDetectorService.computeConfidence(emptyList())
        assert(confidence == 0.0) { "Empty rules should produce 0.0, got $confidence" }
    }

    @Provide
    fun ruleResults(): Arbitrary<List<RuleResult>> {
        return singleRule().list().ofMinSize(1).ofMaxSize(5)
    }

    @Provide
    fun additionalRules(): Arbitrary<List<RuleResult>> {
        return singleRule().list().ofMinSize(1).ofMaxSize(4)
    }

    @Provide
    fun singleRule(): Arbitrary<RuleResult> {
        val names = Arbitraries.of(
            "large_amount", "foreign_currency", "odd_hours", "card_testing", "duplicate_charge"
        )
        val confidences = Arbitraries.doubles().between(0.0, 1.0)
        val reasons = Arbitraries.of("test reason 1", "test reason 2", "test reason 3")

        return Combinators.combine(names, confidences, reasons)
            .`as` { name, conf, reason -> RuleResult(name, conf, reason) }
    }
}
