package dev.adminos.api.domain.identity

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

data class User(
    val id: UUID = UUID.randomUUID(),
    val email: String,
    val name: String? = null,
    val avatarUrl: String? = null,
    val googleId: String,
    val phone: String? = null,
    val country: String = "IN",
    val timezone: String = "Asia/Kolkata",
    val onboardingStatus: OnboardingStatus = OnboardingStatus.STARTED,
    val role: UserRole = UserRole.OWNER,
    val isActive: Boolean = true,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null
)

enum class OnboardingStatus {
    STARTED, GMAIL_CONNECTED, SMS_GRANTED, FIRST_SYNC_DONE, COMPLETED
}

enum class UserRole {
    OWNER, ADMIN, READONLY
}

/** Serializable DTO returned to clients */
@Serializable
data class UserResponse(
    val id: String,
    val email: String,
    val name: String? = null,
    val avatarUrl: String? = null,
    val onboardingStatus: String,
    val role: String
) {
    companion object {
        fun from(user: User) = UserResponse(
            id = user.id.toString(),
            email = user.email,
            name = user.name,
            avatarUrl = user.avatarUrl,
            onboardingStatus = user.onboardingStatus.name.lowercase(),
            role = user.role.name.lowercase()
        )
    }
}
