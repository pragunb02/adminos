package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.wealth.ImportStatus
import dev.adminos.api.domain.wealth.Quantity
import dev.adminos.api.domain.wealth.UnitPrice
import dev.adminos.api.domain.wealth.imports.HoldingCheck
import dev.adminos.api.domain.wealth.imports.HoldingCheckRepository
import dev.adminos.api.domain.wealth.imports.HoldingIssue
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.WealthHoldingChecksTable
import dev.adminos.api.infrastructure.database.tables.WealthImportBatchesTable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/** Postgres holding checks (AOS-805): "applied" joins the batch, so an undone import's checks drop out. */
class ExposedHoldingCheckRepository : HoldingCheckRepository {
    private val t = WealthHoldingChecksTable
    private val b = WealthImportBatchesTable

    override suspend fun saveAll(checks: List<HoldingCheck>) {
        if (checks.isEmpty()) return
        dbQuery {
            t.batchInsert(checks, shouldReturnGeneratedValues = false) { c ->
                this[t.id] = c.id; this[t.userId] = c.userId; this[t.batchId] = c.batchId; this[t.accountId] = c.accountId
                this[t.instrumentId] = c.instrumentId; this[t.schemeName] = c.schemeName; this[t.statementFrom] = c.statementFrom
                this[t.statementTo] = c.statementTo; this[t.openingUnits] = c.openingUnits.value; this[t.closingUnits] = c.closingUnits.value
                this[t.valuationDate] = c.valuationDate; this[t.valuationNav] = c.valuationNav?.value; this[t.valuationValue] = c.valuationValue
                this[t.issue] = c.issue?.wire; this[t.createdAt] = c.createdAt
            }
        }
    }

    override suspend fun applied(userId: UUID): List<HoldingCheck> = dbQuery {
        t.join(b, JoinType.INNER, t.batchId, b.id).selectAll()
            .where { (t.userId eq userId) and (b.userId eq userId) and (b.status eq ImportStatus.APPLIED.wire) }
            .map { it.toCheck() }
    }

    override suspend fun forBatch(userId: UUID, batchId: UUID): List<HoldingCheck> = dbQuery {
        t.selectAll().where { (t.userId eq userId) and (t.batchId eq batchId) }.map { it.toCheck() }
    }

    private fun ResultRow.toCheck() = HoldingCheck(
        id = this[t.id], userId = this[t.userId], batchId = this[t.batchId], accountId = this[t.accountId], instrumentId = this[t.instrumentId],
        schemeName = this[t.schemeName], statementFrom = this[t.statementFrom], statementTo = this[t.statementTo],
        openingUnits = Quantity.of(this[t.openingUnits]), closingUnits = Quantity.of(this[t.closingUnits]), valuationDate = this[t.valuationDate],
        valuationNav = this[t.valuationNav]?.let(UnitPrice::of), valuationValue = this[t.valuationValue],
        issue = this[t.issue]?.let { i -> HoldingIssue.entries.first { it.wire == i } }, createdAt = this[t.createdAt]
    )
}
