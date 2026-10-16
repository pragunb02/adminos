package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.financial.CategoryRule
import dev.adminos.api.domain.financial.CategoryRuleRepository
import dev.adminos.api.domain.financial.RuleMatch
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.CategoryRulesTable as T
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.util.UUID

class ExposedCategoryRuleRepository : CategoryRuleRepository {

    override suspend fun upsert(rule: CategoryRule): CategoryRule = dbQuery {
        T.upsert(T.userId, T.matchType, T.matchValue, onUpdateExclude = listOf(T.id, T.createdAt)) {
            it[id] = rule.id; it[userId] = rule.userId; it[matchType] = rule.match.name.lowercase(); it[matchValue] = rule.value
            it[category] = rule.category.name.lowercase(); it[subcategory] = rule.subcategory
            it[createdAt] = rule.createdAt; it[updatedAt] = rule.updatedAt
        }
        T.selectAll().where { (T.userId eq rule.userId) and (T.matchType eq rule.match.name.lowercase()) and (T.matchValue eq rule.value) }
            .single().toRule()
    }

    override suspend fun findFor(userId: UUID, vpa: String?, merchant: String?): CategoryRule? = dbQuery {
        val keys = listOfNotNull(vpa?.let { RuleMatch.VPA to it }, merchant?.let { RuleMatch.MERCHANT to it })
        if (keys.isEmpty()) return@dbQuery null
        val rows = T.selectAll().where {
            (T.userId eq userId) and keys.map { (m, v) -> (T.matchType eq m.name.lowercase()) and (T.matchValue eq v) }.reduce { a, b -> a or b }
        }.map { it.toRule() }
        rows.minByOrNull { it.match.ordinal } // VPA before MERCHANT
    }

    private fun ResultRow.toRule() = CategoryRule(
        id = this[T.id], userId = this[T.userId],
        match = RuleMatch.valueOf(this[T.matchType].uppercase()), value = this[T.matchValue],
        category = TransactionCategory.valueOf(this[T.category].uppercase()), subcategory = this[T.subcategory],
        createdAt = this[T.createdAt], updatedAt = this[T.updatedAt]
    )
}
