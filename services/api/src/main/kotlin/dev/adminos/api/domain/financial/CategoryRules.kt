package dev.adminos.api.domain.financial

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What a remembered rule matches on: a UPI ID is precise; a merchant name is the fallback. */
enum class RuleMatch { VPA, MERCHANT }

/** A remembered category correction for one payee (AOS-210, D-41). */
data class CategoryRule(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val match: RuleMatch,
    val value: String,
    val category: TransactionCategory,
    val subcategory: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {
    companion object {
        /** The payee key for [t]: its UPI ID, else its merchant name, normalized; null when it has neither. */
        fun keyOf(t: Transaction): Pair<RuleMatch, String>? =
            t.upiVpa?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { RuleMatch.VPA to it }
                ?: t.merchantName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { RuleMatch.MERCHANT to it }
    }
}

interface CategoryRuleRepository {
    /** Insert, or replace the category of the existing rule with the same user, match and value. */
    suspend fun upsert(rule: CategoryRule): CategoryRule
    /** The rule for this payee: the UPI ID rule wins over the merchant rule. Values are already normalized. */
    suspend fun findFor(userId: UUID, vpa: String?, merchant: String?): CategoryRule?
}

class InMemoryCategoryRuleRepository : CategoryRuleRepository {
    private val store = ConcurrentHashMap<Triple<UUID, RuleMatch, String>, CategoryRule>()

    override suspend fun upsert(rule: CategoryRule): CategoryRule {
        val key = Triple(rule.userId, rule.match, rule.value)
        val saved = store[key]?.let { it.copy(category = rule.category, subcategory = rule.subcategory, updatedAt = rule.updatedAt) } ?: rule
        store[key] = saved
        return saved
    }

    override suspend fun findFor(userId: UUID, vpa: String?, merchant: String?): CategoryRule? =
        vpa?.let { store[Triple(userId, RuleMatch.VPA, it)] } ?: merchant?.let { store[Triple(userId, RuleMatch.MERCHANT, it)] }
}
