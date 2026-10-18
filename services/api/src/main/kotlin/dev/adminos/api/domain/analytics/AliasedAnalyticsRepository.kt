package dev.adminos.api.domain.analytics

import dev.adminos.api.domain.financial.grouping.AliasResolver
import dev.adminos.api.domain.financial.grouping.MerchantAliasRepository
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Facts with merchant names read through the user's confirmed merges (AOS-709, D-75): "NETFLIX.COM"
 * and "Netflix Ent" become one merchant in the console's lists and the weekly briefing. A decorator,
 * so every source (SQL or in memory) gets it the same way and the analytics maths don't change.
 */
class AliasedAnalyticsRepository(
    private val delegate: AnalyticsRepository,
    private val aliases: MerchantAliasRepository
) : AnalyticsRepository {
    override suspend fun facts(userId: UUID, from: LocalDate, to: LocalDate, zone: ZoneId): List<SpendFact> {
        val facts = delegate.facts(userId, from, to, zone)
        val resolver = AliasResolver(aliases.aliases(userId))
        if (resolver.isEmpty) return facts
        return facts.map { f -> resolver.name(f.merchant)?.let { f.copy(merchant = it) } ?: f }
    }
}
