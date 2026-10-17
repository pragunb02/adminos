package dev.adminos.api.infrastructure.database.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Exposed table objects for the Identity domain.
 * Maps 1:1 to db/migration/V2__create_identity_tables.sql
 */

object UsersTable : Table("users") {
    val id = javaUUID("id").autoGenerate()
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
    val id = javaUUID("id").autoGenerate()
    val userId = javaUUID("user_id").references(UsersTable.id)
    val tokenHash = varchar("token_hash", 255)
    val deviceId = javaUUID("device_id").references(DevicesTable.id).nullable()
    val ipAddress = varchar("ip_address", 45).nullable()
    val userAgent = text("user_agent").nullable()
    val expiresAt = timestamp("expires_at")
    val lastActiveAt = timestamp("last_active_at").nullable()
    val createdAt = timestamp("created_at")
    val revokedAt = timestamp("revoked_at").nullable()
    val secondFactorAt = timestamp("second_factor_at").nullable() // V34, AOS-617
    override val primaryKey = PrimaryKey(id)
}

/** Passkeys (V34, AOS-617): public keys only. */
object WebAuthnCredentialsTable : Table("webauthn_credentials") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id").references(UsersTable.id)
    val credentialId = varchar("credential_id", 1400)
    val publicKeyCose = text("public_key_cose")
    val signatureCount = long("signature_count")
    val name = varchar("name", 60)
    val transports = text("transports")
    val createdAt = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Recovery codes (V34, AOS-617): SHA-256 only. */
object RecoveryCodesTable : Table("recovery_codes") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id").references(UsersTable.id)
    val codeHash = varchar("code_hash", 64)
    val createdAt = timestamp("created_at")
    val usedAt = timestamp("used_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** Refresh tokens a session has rotated out (V33, AOS-620): seeing one again means it was stolen. */
object SessionRotatedTokensTable : Table("session_rotated_tokens") {
    val tokenHash = varchar("token_hash", 64)
    val sessionId = javaUUID("session_id").references(SessionsTable.id)
    val rotatedAt = timestamp("rotated_at")
    override val primaryKey = PrimaryKey(tokenHash)
}

object DevicesTable : Table("devices") {
    val id = javaUUID("id").autoGenerate()
    val userId = javaUUID("user_id").references(UsersTable.id)
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

/** Maps to db/migration/V12__create_device_tokens.sql (AOS-203). */
object DeviceTokensTable : Table("device_tokens") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id").references(UsersTable.id)
    val name = varchar("name", 100)
    val scopes = text("scopes")
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")
    val lastUsedAt = timestamp("last_used_at").nullable()
    val revokedAt = timestamp("revoked_at").nullable()
    override val primaryKey = PrimaryKey(id)
}
