package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.domain.financial.ai.AiCategoryAnswer
import dev.adminos.api.domain.financial.ai.AiCategoryCacheRepository
import dev.adminos.api.domain.financial.ai.CachedAiCategory
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.AiCategoryCacheTable as T
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

/** The AI answer cache on Postgres (AOS-512, V28). */
class ExposedAiCategoryCacheRepository : AiCategoryCacheRepository {

    override suspend fun find(merchantKey: String): CachedAiCategory? = dbQuery {
        T.selectAll().where { T.merchantKey eq merchantKey }.singleOrNull()?.toEntry()
    }

    override suspend fun put(entry: CachedAiCategory) {
        dbQuery {
            T.upsert(T.merchantKey) {
                it[merchantKey] = entry.merchantKey
                it[model] = entry.model
                it[category] = entry.answer?.category?.name?.lowercase()
                it[confidence] = entry.answer?.confidence
                it[reason] = entry.answer?.reason
                it[createdAt] = entry.createdAt
            }
        }
    }

    private fun ResultRow.toEntry(): CachedAiCategory {
        val category = this[T.category]
        val confidence = this[T.confidence]
        val reason = this[T.reason]
        val answer = if (category != null && confidence != null && reason != null) {
            AiCategoryAnswer(TransactionCategory.valueOf(category.uppercase()), confidence, reason)
        } else null
        return CachedAiCategory(this[T.merchantKey], this[T.model], answer, this[T.createdAt])
    }
}
