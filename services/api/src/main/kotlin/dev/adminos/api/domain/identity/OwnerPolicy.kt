package dev.adminos.api.domain.identity

/**
 * Who may sign in (AOS-614, D-91): AdminOS has exactly one user, its owner (D-78).
 *
 * - Only the Google account whose verified email is [ownerEmail] may sign in. Compared trimmed and
 *   case-insensitively; Google emails are case-insensitive.
 * - **Pinned:** once that email has signed in, the stored account's Google ID is the owner. Another
 *   Google account presenting the same email (e.g. a recycled Workspace address) is refused.
 * - **Fail closed:** with no owner email configured, nobody can sign in.
 */
class OwnerPolicy(ownerEmail: String) {
    private val owner: String? = normalize(ownerEmail).takeIf { it.isNotEmpty() }

    val isConfigured: Boolean get() = owner != null

    /** The owner's normalised email, or null when none is configured. Server-side only: never shown to a caller. */
    val email: String? get() = owner

    fun isOwnerEmail(email: String?): Boolean = owner != null && email != null && normalize(email) == owner

    companion object {
        fun normalize(email: String): String = email.trim().lowercase()

        /** A usable owner email: one `@`, something on both sides, no spaces. */
        fun isValid(email: String): Boolean = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(normalize(email))
    }
}

/** Sign-in refused: not the owner's Google account. The message never names the owner. */
class NotOwnerException : RuntimeException("This AdminOS belongs to its owner only")
