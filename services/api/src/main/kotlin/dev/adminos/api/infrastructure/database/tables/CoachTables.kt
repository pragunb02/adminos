package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.time
import org.jetbrains.exposed.v1.javatime.timestamp

/** Maps to db/migration/V31__lens_overrides.sql (AOS-901). */
object LensOverridesTable : Table("lens_overrides") {
    val userId = javaUUID("user_id").references(UsersTable.id)
    val lensId = varchar("lens_id", 40)
    val merchantKey = varchar("merchant_key", 255)
    val merchant = varchar("merchant", 255)
    val include = bool("include")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(userId, lensId, merchantKey)
}

/** Maps to db/migration/V35__coach_limits.sql (AOS-904). */
object SpendingLimitsTable : Table("spending_limits") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id").references(UsersTable.id)
    val scopeKind = varchar("scope_kind", 10)
    val scopeValue = varchar("scope_value", 255)
    val label = varchar("label", 255)
    val period = varchar("period", 10)
    val amount = decimal("amount", 12, 2)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object LimitAlertsTable : Table("limit_alerts") {
    val limitId = javaUUID("limit_id").references(SpendingLimitsTable.id)
    val periodStart = date("period_start")
    val threshold = short("threshold")
    val claimedAt = timestamp("claimed_at")
    override val primaryKey = PrimaryKey(limitId, periodStart, threshold)
}

object LimitSuggestionDismissalsTable : Table("limit_suggestion_dismissals") {
    val userId = javaUUID("user_id").references(UsersTable.id)
    val lensId = varchar("lens_id", 40)
    val dismissedAt = timestamp("dismissed_at")
    override val primaryKey = PrimaryKey(userId, lensId)
}

object CoachSettingsTable : Table("coach_settings") {
    val userId = javaUUID("user_id").references(UsersTable.id)
    val limitAlerts = bool("limit_alerts")
    val eveningDigest = bool("evening_digest")
    val digestTime = time("digest_time")
    val digestSkipZeroDay = bool("digest_skip_zero_day")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(userId)
}

/** Maps to db/migration/V42__coach_digest.sql (AOS-905): one row per (user, kind, day) sent. */
object CoachDigestsSentTable : Table("coach_digests_sent") {
    val userId = javaUUID("user_id").references(UsersTable.id)
    val kind = varchar("kind", 20)
    val day = date("day")
    val sentAt = timestamp("sent_at")
    override val primaryKey = PrimaryKey(userId, kind, day)
}
