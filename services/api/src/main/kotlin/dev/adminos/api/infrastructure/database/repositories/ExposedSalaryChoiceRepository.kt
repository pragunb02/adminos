package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.forecast.SalaryChoice
import dev.adminos.api.domain.forecast.SalaryChoiceKind
import dev.adminos.api.domain.forecast.SalaryChoiceRepository
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.SalaryChoicesTable as T
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.UUID

/** Postgres [SalaryChoiceRepository] (AOS-722): one row per user; a new choice replaces it and keeps its created_at. */
class ExposedSalaryChoiceRepository : SalaryChoiceRepository {

    override suspend fun find(userId: UUID): SalaryChoice? = dbQuery {
        T.selectAll().where { T.userId eq userId }.singleOrNull()?.toChoice()
    }

    override suspend fun put(userId: UUID, choice: SalaryChoice): SalaryChoice = dbQuery {
        T.upsert(T.userId, onUpdateExclude = listOf(T.userId, T.createdAt)) {
            it[T.userId] = userId; it[payer] = choice.payer; it[payDay] = choice.day.toShort(); it[amount] = choice.amount
            it[kind] = choice.kind.name.lowercase(); it[createdAt] = choice.updatedAt; it[updatedAt] = choice.updatedAt
        }
        choice
    }

    private fun ResultRow.toChoice() = SalaryChoice(
        payer = this[T.payer], day = this[T.payDay].toInt(), amount = this[T.amount],
        kind = SalaryChoiceKind.valueOf(this[T.kind].uppercase()), updatedAt = this[T.updatedAt]
    )
}
