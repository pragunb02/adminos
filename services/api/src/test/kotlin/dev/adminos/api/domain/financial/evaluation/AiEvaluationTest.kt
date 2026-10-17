package dev.adminos.api.domain.financial.evaluation

import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.ai.AiCategoryAnswer
import dev.adminos.api.domain.financial.ai.AiCategoryClient
import dev.adminos.api.domain.financial.ai.AiCategoryResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AOS-512 measured by AOS-511 (D-61, D-62): rules + the AI fallback on the golden set, with recorded answers.
 * The AI may only fill in rows the rules left as Other; it never changes a row the rules decided, and it
 * never sees a person's payment.
 */
class AiEvaluationTest {

    private val golden = GoldenSet.load()
    private val rules = runBlocking { PipelineLabeller().labelAll(golden) }.associateBy { it.case.id }

    private fun withAi(client: AiCategoryClient) = runBlocking { AiLabeller(client).labelAll(golden) }.associateBy { it.case.id }

    @Test
    fun `rules plus AI scores no lower than its baseline on the golden set`() {
        val report = rulesAiReport()
        val baseline = Baselines.load(Baselines.RULES_AI)
        assertEquals(baseline.goldenRows, report.total, "golden set changed size: re-record with ./gradlew -q categorizationReport -PwriteBaseline")
        val drops = RegressionGate.regressions(report, baseline)
        if (drops.isNotEmpty()) fail("Rules + AI got worse on the golden set:\n" + drops.joinToString("\n") { "  - $it" })
    }

    @Test
    fun `rules plus AI is at least as accurate as the rules alone`() {
        val ai = rulesAiReport()
        val alone = rulesReport()
        assertTrue(ai.accuracy >= alone.accuracy, "rules+ai ${ai.accuracy} < rules ${alone.accuracy}")
        assertTrue(ai.bySource.getValue("ai") > 0)
    }

    @Test
    fun `the AI never changes a row the rules decided and only touches rows left as Other`() {
        val after = withAi(RecordedAiClient.load())
        for ((id, r) in rules) {
            val a = after.getValue(id)
            if (r.predicted.label.category != TransactionCategory.OTHER) assertEquals(r.predicted, a.predicted, "$id was decided by the rules")
            if (a.predicted != r.predicted) {
                assertEquals(TransactionCategory.OTHER, r.predicted.label.category, "$id changed but wasn't Other")
                assertEquals("ai", a.predicted.source)
            }
        }
    }

    @Test
    fun `even a model that always says Food with full confidence cannot touch rule-decided rows or payments to people`() {
        val reckless = AiCategoryClient { _, _ -> AiCategoryResult.Answered(AiCategoryAnswer(TransactionCategory.FOOD, 0.99, "always food"), "reckless") }
        val after = withAi(reckless)
        for ((id, r) in rules) {
            val a = after.getValue(id)
            if (r.predicted.label.category != TransactionCategory.OTHER || "p2p" in r.case.tags) assertEquals(r.predicted, a.predicted, id)
        }
        val ruleDecided = rules.values.filter { it.predicted.label.category != TransactionCategory.OTHER }.map { it.case.id }
        assertEquals(ruleDecided.count { rules.getValue(it).correct }, ruleDecided.count { after.getValue(it).correct })
    }

    @Test
    fun `no payment to a person is ever shown to the model, and every question asked is recorded`() {
        val client = RecordedAiClient.load()
        val labeller = AiLabeller(client)
        val askedBy = mutableMapOf<String, String>()
        runBlocking {
            for (c in golden) {
                val before = client.asked.size
                labeller.label(c)
                client.asked.drop(before).forEach { askedBy[it.merchant] = c.id }
            }
        }
        val p2p = golden.filter { "p2p" in it.tags }.map { it.id }.toSet()
        assertEquals(emptySet(), askedBy.values.toSet().intersect(p2p))
        assertEquals(emptySet(), askedBy.keys - client.merchants, "re-record with -PrecordAi or add rows to ai-recorded.csv")
        assertTrue(client.asked.map { it.merchant }.let { it.size == it.toSet().size }, "each merchant is asked once")
    }

    @Test
    fun `the recording loader rejects malformed and duplicate rows`() {
        assertFailsWith<IllegalStateException> { RecordedAiClient.load("/categorization/missing.csv") }
        val header = "merchant,category,confidence,reason"
        assertFailsWith<IllegalArgumentException> { RecordedAiClient.parse("$header\nSteam,ENTERTAINMENT,0.9") }
        assertFailsWith<IllegalArgumentException> { RecordedAiClient.parse("$header\nSteam,GAMES,0.9,x") }
        assertFailsWith<IllegalArgumentException> { RecordedAiClient.parse("$header\nSteam,ENTERTAINMENT,0.9,x\nSteam,FOOD,0.5,y") }
        assertEquals(setOf("Steam"), RecordedAiClient.parse("# note\n$header\nSteam,ENTERTAINMENT,0.9,x").merchants)
    }
}
