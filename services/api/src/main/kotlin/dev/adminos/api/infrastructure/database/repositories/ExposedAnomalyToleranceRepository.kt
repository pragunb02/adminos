package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.agent.AlertFeedback
import dev.adminos.api.domain.agent.AnomalyTolerance
import dev.adminos.api.domain.agent.AnomalyToleranceRepository
import dev.adminos.api.domain.agent.TolerableRule
import dev.adminos.api.domain.agent.baselines.ToleranceLevel
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.dbQueryReadCommitted
import dev.adminos.api.infrastructure.database.tables.AnomalyTolerancesTable as T
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

/** V44 `anomaly_tolerances` (AOS-713). */
class ExposedAnomalyToleranceRepository : AnomalyToleranceRepository {

    override suspend fun find(userId: UUID, merchantKey: String, rule: TolerableRule): AnomalyTolerance? = dbQuery {
        T.selectAll().where { key(userId, merchantKey, rule) }.singleOrNull()?.toTolerance()
    }

    /** Insert-if-absent, then lock the row and move it: two answers at once both count, and the level stays in range. */
    override suspend fun record(userId: UUID, merchantKey: String, rule: TolerableRule, feedback: AlertFeedback, at: Instant): AnomalyTolerance = dbQueryReadCommitted {
        T.insertIgnore {
            it[T.userId] = userId; it[T.merchantKey] = merchantKey; it[T.rule] = rule.code; it[updatedAt] = at
        }
        val current = T.selectAll().where { key(userId, merchantKey, rule) }
            .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate()).single().toTolerance()
        val next = current.after(feedback, at)
        T.update({ key(userId, merchantKey, rule) }) {
            it[level] = next.level.steps.toShort(); it[dismissals] = next.dismissals; it[confirmations] = next.confirmations; it[updatedAt] = at
        }
        next
    }

    private fun key(userId: UUID, merchantKey: String, rule: TolerableRule) =
        (T.userId eq userId) and (T.merchantKey eq merchantKey) and (T.rule eq rule.code)

    private fun ResultRow.toTolerance() = AnomalyTolerance(
        userId = this[T.userId],
        merchantKey = this[T.merchantKey],
        rule = TolerableRule.fromCode(this[T.rule]) ?: throw IllegalStateException("Unknown tolerable rule '${this[T.rule]}' in anomaly_tolerances"),
        level = ToleranceLevel(this[T.level].toInt()),
        dismissals = this[T.dismissals],
        confirmations = this[T.confirmations],
        updatedAt = this[T.updatedAt]
    )
}
