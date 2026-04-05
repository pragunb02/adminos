package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Exposed table objects for the Identity domain.
 * Maps 1:1 to infra/migrations/002_create_identity_tables.sql
 */

object UsersTable : Table("users") {
    val id = uuid("id").autoGenerate()
    val email = varchar("email", 255)
    val name = varchar("name", 255).nullable()
    val avatarUrl = varchar("avatar_url", 500).nullable()
    val googleId = varchar("google_id", 255)
    val phone = varchar("phone", 20).nullable()
    val country = varchar("country", 10).default("IN")
    val timezone = varchar("timezone", 50).default("Asia/Kolkata")
    val onboardingStatus = varchar("onboarding_status", 20).default("started")
    val role = varchar("role", 20).default("owner")
    val isActive = bool("is_active").default(true)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deletedAt = timestamp("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object SessionsTable : Table("sessions") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(UsersTable.id)
    val tokenHash = varchar("token_hash", 255)
    val deviceId = uuid("device_id").references(DevicesTable.id).nullable()
    val ipAddress = varchar("ip_address", 45).nullable()
    val userAgent = text("user_agent").nullable()
    val expiresAt = timestamp("expires_at")
    val lastActiveAt = timestamp("last_active_at").nullable()
    val createdAt = timestamp("created_at")
    val revokedAt = timestamp("revoked_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object DevicesTable : Table("devices") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(UsersTable.id)
    val deviceType = varchar("device_type", 20)
    val deviceName = varchar("device_name", 255).nullable()
    val fcmToken = text("fcm_token").nullable()
    val appVersion = varchar("app_version", 20).nullable()
    val osVersion = varchar("os_version", 20).nullable()
    val smsPermission = bool("sms_permission").default(false)
    val lastSeenAt = timestamp("last_seen_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}
