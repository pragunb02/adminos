package dev.adminos.api.domain.ai.eval

import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.domain.ai.AiProviderKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** AOS-702, D-67: a feature can't be enabled for a provider/model/prompt without a passing committed eval. */
class AiEvalGateTest {

    private val record = AiEvalRecord(
        feature = "categorization", provider = "ollama", model = "qwen3:4b", promptVersion = "categorize-merchant-v1",
        answers = AiEvalAnswers.LIVE, evaluatedOn = "2026-10-17", goldenRows = 288,
        metrics = mapOf("accuracy" to 0.9, "f1.FOOD" to 0.95), floor = mapOf("accuracy" to 0.9, "f1.FOOD" to 0.9)
    )
    private val key = AiEvalKey(AiFeature.CATEGORIZATION, AiProviderKind.OLLAMA, "qwen3:4b", "categorize-merchant-v1")
    private val records = AiEvalRecords(listOf(record))

    @Test
    fun `a record that meets its floor passes`() {
        assertEquals(AiEvalVerdict.Passed(record), AiEvalGate.verdict(records, key))
        AiEvalGate.requirePassing(records, listOf(key))
    }

    @Test
    fun `a different model, provider or prompt version is not evaluated`() {
        for (other in listOf(key.copy(model = "qwen3:8b"), key.copy(provider = AiProviderKind.ANTHROPIC), key.copy(promptVersion = "categorize-merchant-v2"))) {
            assertEquals(AiEvalVerdict.NotEvaluated, AiEvalGate.verdict(records, other))
        }
        val e = assertFailsWith<IllegalStateException> { AiEvalGate.requirePassing(records, listOf(key.copy(model = "qwen3:8b"))) }
        assertContains(e.message.orEmpty(), "qwen3:8b")
        assertContains(e.message.orEmpty(), "no eval record")
    }

    @Test
    fun `a metric below its floor, or missing, fails and is named`() {
        val low = record.copy(metrics = mapOf("accuracy" to 0.85))
        val verdict = AiEvalGate.verdict(AiEvalRecords(listOf(low)), key)
        assertIs<AiEvalVerdict.BelowFloor>(verdict)
        assertEquals(listOf(MetricDrop("accuracy", 0.9, 0.85), MetricDrop("f1.FOOD", 0.9, null)), verdict.drops)
        val e = assertFailsWith<IllegalStateException> { AiEvalGate.requirePassing(AiEvalRecords(listOf(low)), listOf(key)) }
        assertContains(e.message.orEmpty(), "accuracy is 0.85, below its floor 0.9")
        assertContains(e.message.orEmpty(), "f1.FOOD is missing")
    }

    @Test
    fun `rounding noise is not a drop and NaN is`() {
        assertEquals(emptyList(), AiEvalGate.drops(mapOf("a" to 0.9 - 1e-12), mapOf("a" to 0.9)))
        assertEquals(1, AiEvalGate.drops(mapOf("a" to Double.NaN), mapOf("a" to 0.0)).size)
    }

    @Test
    fun `records round-trip as JSON, replace by key and refuse duplicates`() {
        val again = AiEvalRecords.parse(records.toJson())
        assertEquals(records, again)
        val updated = records.with(record.copy(metrics = mapOf("accuracy" to 1.0, "f1.FOOD" to 1.0)))
        assertEquals(1, updated.records.size)
        assertEquals(1.0, updated.records.single().metrics["accuracy"])
        assertEquals(2, records.with(record.copy(model = "gemma3:4b")).records.size)
        assertFailsWith<IllegalArgumentException> { AiEvalRecords(listOf(record, record)) }
        assertFailsWith<IllegalStateException> { AiEvalRecords.parse("{not json") }
        assertFailsWith<IllegalStateException> { AiEvalRecords.load("/ai/missing.json") }
    }

    @Test
    fun `a record names a known feature and provider and has a floor`() {
        assertFailsWith<IllegalArgumentException> { record.copy(feature = "mind-reading") }
        assertFailsWith<IllegalArgumentException> { record.copy(provider = "openai") }
        assertFailsWith<IllegalArgumentException> { record.copy(model = " ") }
        assertFailsWith<IllegalArgumentException> { record.copy(goldenRows = 0) }
        assertFailsWith<IllegalArgumentException> { record.copy(floor = emptyMap()) }
        assertEquals(key, record.key)
    }

    @Test
    fun `the committed records load and every one passes its own floor`() {
        val committed = AiEvalRecords.load()
        for (r in committed.records) assertIs<AiEvalVerdict.Passed>(AiEvalGate.verdict(committed, r.key), "${r.key}")
    }
}
