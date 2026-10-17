package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.ai.AiCallLogRepository
import dev.adminos.api.domain.ai.AiCallRecord
import dev.adminos.api.domain.ai.AiFeature
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.AiCallsTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

/** The AI call audit log on Postgres (AOS-702). Rows hold ids, counts and codes only. */
class ExposedAiCallLogRepository : AiCallLogRepository {

    override suspend fun record(call: AiCallRecord) {
        dbQuery {
            AiCallsTable.insert {
                it[id] = call.id
                it[userId] = call.userId
                it[feature] = call.feature.key
                it[provider] = call.provider.key
                it[model] = call.model
                it[promptVersion] = call.promptVersion
                it[inputTokens] = call.usage.inputTokens
                it[outputTokens] = call.usage.outputTokens
                it[costMicros] = call.costMicros
                it[outcome] = call.outcome.name.lowercase()
                it[code] = call.code?.value
                it[latencyMs] = call.latencyMs
                it[createdAt] = call.at
            }
        }
    }

    override suspend fun spentMicros(userId: UUID, feature: AiFeature, month: YearMonth): Long = dbQuery {
        val t = AiCallsTable
        val total = t.costMicros.sum()
        t.select(total)
            .where { (t.userId eq userId) and (t.feature eq feature.key) and (t.createdAt greaterEq start(month)) and (t.createdAt less start(month.plusMonths(1))) }
            .single()[total] ?: 0L
    }

    private fun start(month: YearMonth): Instant = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC)
}
