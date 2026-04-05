package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.agent.*
import dev.adminos.api.infrastructure.database.CursorCodec
import dev.adminos.api.infrastructure.database.tables.BriefingsTable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class ExposedBriefingRepository : BriefingRepository {

    override suspend fun save(briefing: Briefing): Briefing = newSuspendedTransaction {
        BriefingsTable.insert {
            it[id] = briefing.id
            it[userId] = briefing.userId
            it[periodStart] = briefing.periodStart
            it[periodEnd] = briefing.periodEnd
            it[type] = briefing.type.name.lowercase()
            it[content] = briefing.content
            it[contentStructured] = briefing.contentStructured?.let { m ->
                Json.encodeToString(kotlinx.serialization.serializer(), m)
            }
            it[totalSpent] = briefing.totalSpent
            it[totalIncome] = briefing.totalIncome
            it[topCategories] = briefing.topCategories?.let { cats ->
                Json.encodeToString(kotlinx.serialization.serializer(), cats.map { c ->
                    mapOf("category" to c.category, "amount" to c.amount.toString())
                })
            }
            it[subscriptionsFlagged] = briefing.subscriptionsFlagged
            it[anomaliesDetected] = briefing.anomaliesDetected
            it[billsUpcoming] = briefing.billsUpcoming
            it[status] = briefing.status.name.lowercase()
            it[deliveredAt] = briefing.deliveredAt
            it[openedAt] = briefing.openedAt
            it[modelUsed] = briefing.modelUsed
            it[promptVersion] = briefing.promptVersion
            it[tokensUsed] = briefing.tokensUsed
            it[generationMs] = briefing.generationMs
            it[createdAt] = briefing.createdAt
            it[updatedAt] = briefing.updatedAt
        }
        briefing
    }

    override suspend fun findById(id: UUID): Briefing? = newSuspendedTransaction {
        BriefingsTable.select { BriefingsTable.id eq id }.singleOrNull()?.toBriefing()
    }

    override suspend fun findLatestByUserId(userId: UUID): Briefing? = newSuspendedTransaction {
        BriefingsTable.select { BriefingsTable.userId eq userId }
            .orderBy(BriefingsTable.createdAt, SortOrder.DESC)
            .limit(1)
            .singleOrNull()?.toBriefing()
    }

    override suspend fun findByUserId(userId: UUID, cursor: String?, limit: Int): List<Briefing> =
        newSuspendedTransaction {
            val query = BriefingsTable.select { BriefingsTable.userId eq userId }
            cursor?.let { c ->
                val (key, _) = CursorCodec.decode(c)
                val cursorInstant = Instant.parse(key)
                query.andWhere { BriefingsTable.createdAt less cursorInstant }
            }
            query.orderBy(BriefingsTable.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { it.toBriefing() }
        }

    override suspend fun countByUserId(userId: UUID): Long = newSuspendedTransaction {
        BriefingsTable.select { BriefingsTable.userId eq userId }.count()
    }

    @Suppress("UNCHECKED_CAST")
    private fun ResultRow.toBriefing(): Briefing {
        val topCats = this[BriefingsTable.topCategories]?.let { raw ->
            val list = Json.decodeFromString<List<Map<String, String>>>(raw)
            list.map { CategoryAmount(it["category"] ?: "", BigDecimal(it["amount"] ?: "0")) }
        }
        return Briefing(
            id = this[BriefingsTable.id],
            userId = this[BriefingsTable.userId],
            periodStart = this[BriefingsTable.periodStart],
            periodEnd = this[BriefingsTable.periodEnd],
            type = BriefingType.valueOf(this[BriefingsTable.type].uppercase()),
            content = this[BriefingsTable.content],
            contentStructured = this[BriefingsTable.contentStructured]?.let {
                Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(it) as? Map<String, Any?>
            },
            totalSpent = this[BriefingsTable.totalSpent],
            totalIncome = this[BriefingsTable.totalIncome],
            topCategories = topCats,
            subscriptionsFlagged = this[BriefingsTable.subscriptionsFlagged],
            anomaliesDetected = this[BriefingsTable.anomaliesDetected],
            billsUpcoming = this[BriefingsTable.billsUpcoming],
            status = BriefingStatus.valueOf(this[BriefingsTable.status].uppercase()),
            deliveredAt = this[BriefingsTable.deliveredAt],
            openedAt = this[BriefingsTable.openedAt],
            modelUsed = this[BriefingsTable.modelUsed],
            promptVersion = this[BriefingsTable.promptVersion],
            tokensUsed = this[BriefingsTable.tokensUsed],
            generationMs = this[BriefingsTable.generationMs],
            createdAt = this[BriefingsTable.createdAt],
            updatedAt = this[BriefingsTable.updatedAt]
        )
    }
}
