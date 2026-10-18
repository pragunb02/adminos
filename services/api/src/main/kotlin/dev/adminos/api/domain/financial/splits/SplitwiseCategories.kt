package dev.adminos.api.domain.financial.splits

import dev.adminos.api.domain.financial.TransactionCategory

/**
 * Splitwise's own categories (the export shows the subcategory, e.g. "Dining out") mapped to ours (AOS-911).
 * Splitwise's list is fixed (`get_categories` in its API docs), so this is a small table rather than catalogue data.
 * Anything unknown, "General" included, is [TransactionCategory.OTHER], and the merchant catalogue may still place it.
 */
object SplitwiseCategories {
    private val MAP: Map<String, TransactionCategory> = buildMap {
        listOf("food and drink", "dining out", "groceries", "liquor").forEach { put(it, TransactionCategory.FOOD) }
        listOf("transportation", "bicycle", "bus/train", "car", "gas/fuel", "parking", "plane", "taxi").forEach { put(it, TransactionCategory.TRANSPORT) }
        listOf("entertainment", "games", "movies", "music", "sports").forEach { put(it, TransactionCategory.ENTERTAINMENT) }
        listOf("utilities", "cleaning", "electricity", "heat/gas", "internet", "trash", "tv/phone", "water").forEach { put(it, TransactionCategory.UTILITIES) }
        listOf("clothing", "electronics", "furniture", "household supplies", "gifts").forEach { put(it, TransactionCategory.SHOPPING) }
        put("medical expenses", TransactionCategory.HEALTH)
        put("education", TransactionCategory.EDUCATION)
    }

    fun categoryOf(splitwiseCategory: String?): TransactionCategory =
        splitwiseCategory?.trim()?.lowercase()?.let { MAP[it] } ?: TransactionCategory.OTHER
}
