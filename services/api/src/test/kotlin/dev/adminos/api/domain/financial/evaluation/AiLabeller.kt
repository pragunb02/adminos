package dev.adminos.api.domain.financial.evaluation

import dev.adminos.api.domain.financial.CategorizationService
import dev.adminos.api.domain.financial.CategoryRuleRepository
import dev.adminos.api.domain.financial.InMemoryCategoryRuleRepository
import dev.adminos.api.domain.financial.InMemoryTransactionRepository
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.ai.AiCategorizationService
import dev.adminos.api.domain.financial.ai.AiCategoryAnswer
import dev.adminos.api.domain.financial.ai.AiCategoryClient
import dev.adminos.api.domain.financial.ai.AiCategoryQuestion
import dev.adminos.api.domain.financial.ai.AiCategoryResult
import dev.adminos.api.domain.financial.ai.InMemoryAiCategoryCacheRepository
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A deterministic stand-in for Claude (AOS-512): answers from a recorded table, merchant name → answer.
 * A question with no recording is [AiCategoryResult.Unavailable], so a gap shows up instead of a guess.
 * Every question asked is kept, so tests can check exactly what would have left AdminOS.
 */
class RecordedAiClient(private val answers: Map<String, AiCategoryAnswer>, private val model: String = MODEL) : AiCategoryClient {
    val asked: MutableList<AiCategoryQuestion> = CopyOnWriteArrayList()
    val merchants: Set<String> get() = answers.keys

    override suspend fun ask(userId: UUID, question: AiCategoryQuestion): AiCategoryResult {
        asked += question
        return answers[question.merchant]?.let { AiCategoryResult.Answered(it, model) } ?: AiCategoryResult.Unavailable("not recorded")
    }

    companion object {
        const val MODEL = "recorded"
        const val RESOURCE = "/categorization/ai-recorded.csv"

        /** `merchant,category,confidence,reason`; `#` lines are comments. */
        fun load(resource: String = RESOURCE): RecordedAiClient =
            parse(RecordedAiClient::class.java.getResource(resource)?.readText() ?: throw IllegalStateException("recording not found: $resource"))

        fun parse(text: String): RecordedAiClient {
            val rows = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.drop(1).mapIndexed { i, line ->
                val f = line.split(',').map { it.trim() }
                require(f.size == 4) { "ai recording row ${i + 1}: expected 4 fields, found ${f.size}" }
                f[0] to AiCategoryAnswer(TransactionCategory.valueOf(f[1]), f[2].toDouble(), f[3])
            }
            val duplicate = rows.groupBy { it.first }.filterValues { it.size > 1 }.keys
            require(duplicate.isEmpty()) { "ai recording: duplicate merchants $duplicate" }
            return RecordedAiClient(rows.toMap())
        }
    }
}

/**
 * Labels golden cases with the rules **and then** the AI fallback, as production does: the pipeline first,
 * then [AiCategorizationService] on the stored transaction (the job's step). Same user, rules and cache for
 * the whole run, so a merchant is asked once.
 */
class AiLabeller(
    val client: AiCategoryClient,
    private val rules: CategoryRuleRepository = InMemoryCategoryRuleRepository(),
    private val userId: UUID = UUID(0, 512)
) {
    private val txns = InMemoryTransactionRepository()
    private val pipeline = PipelineLabeller(CategorizationService(rules), userId = userId)
    private val ai = AiCategorizationService(client, InMemoryAiCategoryCacheRepository(), rules, txns, RecordedAiClient.MODEL)

    suspend fun label(case: LabelledCase): Outcome {
        val stored = txns.save(pipeline.transactionFor(case))
        ai.categorize(stored)
        val t = txns.findById(stored.id) ?: error("stored transaction vanished")
        return Outcome(case, Prediction(Label(t.category, t.subcategory), t.categorySource))
    }

    suspend fun labelAll(cases: List<LabelledCase>): List<Outcome> = cases.map { label(it) }
}
