package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.agent.*
import dev.adminos.api.domain.ai.AiReasonCode
import dev.adminos.api.domain.common.CursorCodec
import dev.adminos.api.infrastructure.database.tables.BriefingsTable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import dev.adminos.api.infrastructure.database.JsonColumns
import dev.adminos.api.infrastructure.database.dbQuery
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class ExposedBriefingRepository : BriefingRepository {
    private companion object {
        const val NARRATION_NONE = "none"
        const val NARRATION_NARRATED = "narrated"
        const val NARRATION_REJECTED = "rejected"
        const val NARRATION_UNAVAILABLE = "unavailable"
    }

    override suspend fun save(briefing: Briefing): Briefing = dbQuery {
        BriefingsTable.insert {
            it[id] = briefing.id
            it[userId] = briefing.userId
            it[periodStart] = briefing.periodStart
            it[periodEnd] = briefing.periodEnd
            it[type] = briefing.type.name.lowercase()
            it[content] = briefing.content
            it[contentStructured] = briefing.contentStructured?.let { m ->
                JsonColumns.encode(m)
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
            writeNarration(it, briefing.narration)
            it[createdAt] = briefing.createdAt
            it[updatedAt] = briefing.updatedAt
        }
        briefing
    }

    override suspend fun findByUserAndPeriod(userId: UUID, periodStart: java.time.LocalDate, type: BriefingType): Briefing? = dbQuery {
        BriefingsTable.selectAll().where {
            (BriefingsTable.userId eq userId) and (BriefingsTable.periodStart eq periodStart) and (BriefingsTable.type eq type.name.lowercase())
        }.singleOrNull()?.toBriefing()
    }

    override suspend fun findById(id: UUID): Briefing? = dbQuery {
        BriefingsTable.selectAll().where { BriefingsTable.id eq id }.singleOrNull()?.toBriefing()
    }

    override suspend fun findLatestByUserId(userId: UUID): Briefing? = dbQuery {
        BriefingsTable.selectAll().where { BriefingsTable.userId eq userId }
            .orderBy(BriefingsTable.createdAt, SortOrder.DESC)
            .limit(1)
            .singleOrNull()?.toBriefing()
    }

    override suspend fun findByUserId(userId: UUID, cursor: String?, limit: Int): List<Briefing> =
        dbQuery {
            val query = BriefingsTable.selectAll().where { BriefingsTable.userId eq userId }
            cursor?.let { c ->
                val (key, _) = CursorCodec.decode(c)
                val cursorInstant = Instant.parse(key)
                query.andWhere { BriefingsTable.createdAt less cursorInstant }
            }
            query.orderBy(BriefingsTable.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { it.toBriefing() }
        }

    override suspend fun countByUserId(userId: UUID): Long = dbQuery {
        BriefingsTable.selectAll().where { BriefingsTable.userId eq userId }.count()
    }

    override suspend fun updateNarration(id: UUID, narration: BriefingNarration, at: Instant) {
        dbQuery {
            BriefingsTable.update({ BriefingsTable.id eq id }) {
                writeNarration(it, narration)
                it[updatedAt] = at
            }
        }
    }

    /** One place maps [BriefingNarration] to its columns (V90's shape check holds them together). */
    private fun writeNarration(row: org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>, n: BriefingNarration) {
        row[BriefingsTable.narrationStatus] = when (n) {
            BriefingNarration.None -> NARRATION_NONE
            is BriefingNarration.Narrated -> NARRATION_NARRATED
            is BriefingNarration.Rejected -> NARRATION_REJECTED
            is BriefingNarration.Unavailable -> NARRATION_UNAVAILABLE
        }
        row[BriefingsTable.narration] = (n as? BriefingNarration.Narrated)?.text
        row[BriefingsTable.narrationModel] = (n as? BriefingNarration.Narrated)?.model
        row[BriefingsTable.narrationCode] = when (n) {
            is BriefingNarration.Rejected -> n.code.value
            is BriefingNarration.Unavailable -> n.code.value
            else -> null
        }
    }

    private fun ResultRow.toNarration(): BriefingNarration {
        val code = this[BriefingsTable.narrationCode]?.let(::AiReasonCode)
        return when (val status = this[BriefingsTable.narrationStatus]) {
            NARRATION_NONE -> BriefingNarration.None
            NARRATION_NARRATED -> BriefingNarration.Narrated(
                this[BriefingsTable.narration] ?: error("narrated briefing without text (V90 check)"),
                this[BriefingsTable.narrationModel] ?: error("narrated briefing without a model (V90 check)")
            )
            NARRATION_REJECTED -> BriefingNarration.Rejected(code ?: error("rejected narration without a code (V90 check)"))
            NARRATION_UNAVAILABLE -> BriefingNarration.Unavailable(code ?: error("unavailable narration without a code (V90 check)"))
            else -> error("Unknown narration status '$status'")
        }
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
                JsonColumns.decode(it)
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
            narration = toNarration(),
            createdAt = this[BriefingsTable.createdAt],
            updatedAt = this[BriefingsTable.updatedAt]
        )
    }
}
