package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.coach.digest.DigestKind
import dev.adminos.api.domain.coach.digest.DigestLedger
import dev.adminos.api.domain.coach.lenses.LensId
import dev.adminos.api.domain.coach.limits.DuplicateLimitException
import dev.adminos.api.domain.coach.limits.LimitAlertLedger
import dev.adminos.api.domain.coach.limits.LimitPeriod
import dev.adminos.api.domain.coach.limits.LimitScope
import dev.adminos.api.domain.coach.limits.SpendHistory
import dev.adminos.api.domain.coach.limits.SpendingLimit
import dev.adminos.api.domain.coach.limits.SpendingLimitRepository
import dev.adminos.api.domain.coach.limits.SuggestionDismissals
import dev.adminos.api.domain.coach.limits.Threshold
import dev.adminos.api.domain.coach.settings.CoachSettings
import dev.adminos.api.domain.coach.settings.CoachSettingsRepository
import dev.adminos.api.domain.financial.TransactionCategory
import dev.adminos.api.infrastructure.database.dbQuery
import dev.adminos.api.infrastructure.database.tables.CoachDigestsSentTable
import dev.adminos.api.infrastructure.database.tables.CoachSettingsTable
import dev.adminos.api.infrastructure.database.tables.LimitAlertsTable
import dev.adminos.api.infrastructure.database.tables.LimitSuggestionDismissalsTable
import dev.adminos.api.infrastructure.database.tables.SpendingLimitsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Postgres limits (AOS-904). A unique-key clash becomes [DuplicateLimitException]. */
class ExposedSpendingLimitRepository : SpendingLimitRepository {
    private val t = SpendingLimitsTable

    override suspend fun save(limit: SpendingLimit): SpendingLimit = unique(limit) {
        dbQuery {
            t.insert { it[id] = limit.id; it[userId] = limit.userId; it[createdAt] = limit.createdAt; write(it, limit) }
            limit
        }
    }

    override suspend fun update(limit: SpendingLimit): SpendingLimit = unique(limit) {
        dbQuery {
            t.update({ (t.id eq limit.id) and (t.userId eq limit.userId) }) { write(it, limit) }
            limit
        }
    }

    override suspend fun findByUser(userId: UUID): List<SpendingLimit> = dbQuery {
        t.selectAll().where { t.userId eq userId }.orderBy(t.createdAt to SortOrder.ASC).map { it.toLimit() }
    }

    override suspend fun findById(userId: UUID, id: UUID): SpendingLimit? = dbQuery {
        t.selectAll().where { (t.id eq id) and (t.userId eq userId) }.singleOrNull()?.toLimit()
    }

    override suspend fun delete(userId: UUID, id: UUID): Boolean = dbQuery {
        t.deleteWhere { (t.id eq id) and (t.userId eq userId) } > 0
    }

    private suspend fun unique(limit: SpendingLimit, work: suspend () -> SpendingLimit): SpendingLimit = try {
        work()
    } catch (e: ExposedSQLException) {
        if (e.sqlState == UNIQUE_VIOLATION) throw DuplicateLimitException("There is already a ${limit.period.word} limit for ${limit.label}") else throw e
    }

    private fun write(it: UpdateBuilder<*>, l: SpendingLimit) {
        it[t.scopeKind] = l.scope.kind.name.lowercase(); it[t.scopeValue] = l.scope.value; it[t.label] = l.label
        it[t.period] = l.period.name.lowercase(); it[t.amount] = l.amount; it[t.updatedAt] = l.updatedAt
    }

    private fun ResultRow.toLimit() = SpendingLimit(
        id = this[t.id], userId = this[t.userId], scope = scopeOf(this[t.scopeKind], this[t.scopeValue], this[t.label]),
        label = this[t.label], period = LimitPeriod.valueOf(this[t.period].uppercase()), amount = this[t.amount],
        createdAt = this[t.createdAt], updatedAt = this[t.updatedAt]
    )

    private fun scopeOf(kind: String, value: String, label: String): LimitScope = when (LimitScope.Kind.valueOf(kind.uppercase())) {
        LimitScope.Kind.LENS -> LimitScope.Lens(LensId(value))
        // The key is the name lower-cased; the label keeps how it was typed.
        LimitScope.Kind.MERCHANT -> LimitScope.Merchant(label.takeIf { it.trim().lowercase() == value } ?: value)
        LimitScope.Kind.CATEGORY -> LimitScope.Category(TransactionCategory.valueOf(value.uppercase()))
    }

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
    }
}

/** The claim is the primary key: `INSERT … ON CONFLICT DO NOTHING` (AOS-904). */
class ExposedLimitAlertLedger : LimitAlertLedger {
    private val t = LimitAlertsTable

    override suspend fun claim(limitId: UUID, periodStart: LocalDate, threshold: Threshold, at: Instant): Boolean = dbQuery {
        t.insertIgnore {
            it[t.limitId] = limitId; it[t.periodStart] = periodStart; it[t.threshold] = threshold.percent.toShort(); it[claimedAt] = at
        }.insertedCount == 1
    }

    override suspend fun claimed(limitId: UUID, periodStart: LocalDate): Set<Threshold> = dbQuery {
        t.selectAll().where { (t.limitId eq limitId) and (t.periodStart eq periodStart) }
            .mapNotNull { row -> Threshold.entries.firstOrNull { it.percent == row[t.threshold].toInt() } }.toSet()
    }
}

class ExposedSuggestionDismissals : SuggestionDismissals {
    private val t = LimitSuggestionDismissalsTable

    override suspend fun dismiss(userId: UUID, lens: LensId, at: Instant) {
        dbQuery { t.upsert(t.userId, t.lensId) { it[t.userId] = userId; it[lensId] = lens.value; it[dismissedAt] = at } }
    }

    override suspend fun dismissedAt(userId: UUID): Map<LensId, Instant> = dbQuery {
        t.selectAll().where { t.userId eq userId }.mapNotNull { r -> LensId.parse(r[t.lensId])?.let { it to r[t.dismissedAt] } }.toMap()
    }
}

class ExposedCoachSettingsRepository : CoachSettingsRepository {
    private val t = CoachSettingsTable

    override suspend fun find(userId: UUID): CoachSettings? = dbQuery {
        t.selectAll().where { t.userId eq userId }.singleOrNull()?.let {
            CoachSettings(it[t.userId], it[t.limitAlerts], it[t.eveningDigest], it[t.digestTime], it[t.digestSkipZeroDay], it[t.updatedAt])
        }
    }

    override suspend fun save(settings: CoachSettings): CoachSettings = dbQuery {
        t.upsert(t.userId) {
            it[userId] = settings.userId; it[limitAlerts] = settings.limitAlerts; it[eveningDigest] = settings.eveningDigest
            it[digestTime] = settings.digestTime; it[digestSkipZeroDay] = settings.digestSkipZeroDay; it[updatedAt] = settings.updatedAt
        }
        settings
    }
}

/** The first spend or refund, straight from Postgres. */
class ExposedSpendHistory : SpendHistory {
    override suspend fun firstSpendAt(userId: UUID): Instant? = dbQuery {
        TransactionManager.current().exec(
            "SELECT min(transacted_at) AS first FROM transactions WHERE user_id = ?::uuid AND nature IN ('spend', 'refund')",
            listOf(TextColumnType() to userId.toString())
        ) { rs -> if (rs.next()) rs.getObject("first", OffsetDateTime::class.java)?.toInstant() else null }
    }
}

/** One digest per (user, kind, day): `INSERT … ON CONFLICT DO NOTHING` is the claim (AOS-905). */
class ExposedDigestLedger : DigestLedger {
    private val t = CoachDigestsSentTable
    override suspend fun claim(userId: UUID, kind: DigestKind, day: LocalDate, at: Instant): Boolean = dbQuery {
        t.insertIgnore { it[t.userId] = userId; it[t.kind] = kind.name.lowercase(); it[t.day] = day; it[sentAt] = at }.insertedCount == 1
    }
}
