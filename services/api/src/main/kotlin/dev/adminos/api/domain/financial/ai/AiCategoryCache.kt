package dev.adminos.api.domain.financial.ai

import dev.adminos.api.domain.financial.TransactionCategory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * One merchant's AI answer, kept so a merchant is asked once (AOS-512, V28). Keyed by the merchant name in
 * matching form (see `MerchantCatalogue.matchForm`). Holds no user, amount or transaction: only the merchant
 * name, which the model saw anyway. A rejected answer is cached too, so a bad merchant isn't asked forever.
 */
data class CachedAiCategory(
    val merchantKey: String,
    val model: String,
    /** The answer, or null when the model's reply was rejected. */
    val answer: AiCategoryAnswer?,
    val createdAt: Instant
) {
    init {
        require(merchantKey.isNotBlank() && merchantKey.length <= 120) { "A merchant key is 1..120 characters" }
        require(model.isNotBlank()) { "A cached answer names its model" }
    }

    fun toResult(): AiCategoryResult = answer?.let { AiCategoryResult.Answered(it, model) } ?: AiCategoryResult.Rejected("cached rejection")

    companion object {
        /** The cache form of a result; [AiCategoryResult.Unavailable] is never cached. */
        fun of(merchantKey: String, result: AiCategoryResult, fallbackModel: String, at: Instant): CachedAiCategory? = when (result) {
            is AiCategoryResult.Answered -> CachedAiCategory(merchantKey, result.model, result.answer, at)
            is AiCategoryResult.Rejected -> CachedAiCategory(merchantKey, fallbackModel, null, at)
            is AiCategoryResult.Unavailable, is AiCategoryResult.Withheld -> null
        }
    }
}

interface AiCategoryCacheRepository {
    suspend fun find(merchantKey: String): CachedAiCategory?
    /** Insert or replace the entry for its merchant key. */
    suspend fun put(entry: CachedAiCategory)
}

class InMemoryAiCategoryCacheRepository : AiCategoryCacheRepository {
    private val store = ConcurrentHashMap<String, CachedAiCategory>()
    override suspend fun find(merchantKey: String): CachedAiCategory? = store[merchantKey]
    override suspend fun put(entry: CachedAiCategory) {
        store[entry.merchantKey] = entry
    }
}

/** The categories a model may choose: every category except Transfer, which only TransferStep decides (D-41). */
val AI_ALLOWED_CATEGORIES: List<TransactionCategory> = TransactionCategory.entries.filter { it != TransactionCategory.TRANSFER }
