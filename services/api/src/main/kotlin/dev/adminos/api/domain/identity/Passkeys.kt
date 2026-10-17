package dev.adminos.api.domain.identity

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A passkey (WebAuthn credential) of the owner (AOS-617). Binary values are base64url text so the model is a plain
 * immutable value. Only the public key is ever here; the private key never leaves the owner's authenticator.
 */
data class Passkey(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    /** The credential ID the authenticator chose (base64url). */
    val credentialId: String,
    /** The COSE-encoded public key (base64url). */
    val publicKeyCose: String,
    val signatureCount: Long,
    val name: String,
    val transports: Set<String> = emptySet(),
    val createdAt: Instant,
    val lastUsedAt: Instant? = null
) {
    init {
        require(credentialId.isNotBlank() && credentialId.length <= 1400) { "credentialId: 1..1400 characters" }
        require(publicKeyCose.isNotBlank()) { "publicKeyCose is required" }
        require(name.isNotBlank() && name.length <= 60) { "name: 1..60 characters" }
        require(signatureCount >= 0) { "signatureCount can't be negative" }
    }
}

interface PasskeyRepository {
    suspend fun save(passkey: Passkey): Passkey
    suspend fun findByUser(userId: UUID): List<Passkey>
    suspend fun recordUse(id: UUID, signatureCount: Long, at: Instant)
}

/** One-time recovery codes (AOS-617): only their SHA-256 is stored. */
interface RecoveryCodeRepository {
    /** Replaces all of [userId]'s codes with [hashes]. */
    suspend fun replaceAll(userId: UUID, hashes: List<String>, at: Instant)
    /** Uses up the unused code with [hash]; false if there is none (atomic: a code works once). */
    suspend fun consume(userId: UUID, hash: String, at: Instant): Boolean
    suspend fun countUnused(userId: UUID): Int
}

/** Who the passkey is for, as the authenticator shows it. The handle is the user's random UUID: no personal data. */
data class PasskeyUser(val handle: ByteArray, val name: String, val displayName: String) {
    override fun equals(other: Any?) = other is PasskeyUser && handle.contentEquals(other.handle) && name == other.name
    override fun hashCode() = handle.contentHashCode()
}

/** A WebAuthn ceremony in progress: [optionsJson] goes to the browser, [requestJson] stays on the server. */
data class Ceremony(val optionsJson: String, val requestJson: String)

data class NewPasskeyCredential(val credentialId: String, val publicKeyCose: String, val signatureCount: Long, val transports: Set<String>)
data class PasskeyUse(val credentialId: String, val signatureCount: Long)

/** The WebAuthn verification port (layer 5): a standards library checks challenges, origins, signatures and flags. */
interface WebAuthnCeremonies {
    fun startRegistration(user: PasskeyUser, existing: List<Passkey>): Ceremony
    /** @throws PasskeyException.Rejected for anything that doesn't verify */
    fun finishRegistration(user: PasskeyUser, existing: List<Passkey>, requestJson: String, responseJson: String): NewPasskeyCredential
    fun startAuthentication(user: PasskeyUser, passkeys: List<Passkey>): Ceremony
    /** @throws PasskeyException.Rejected for anything that doesn't verify */
    fun finishAuthentication(user: PasskeyUser, passkeys: List<Passkey>, requestJson: String, responseJson: String): PasskeyUse
}

sealed class PasskeyException(message: String) : RuntimeException(message) {
    /** The response didn't verify (wrong challenge, origin, key, signature, user verification…). */
    class Rejected(why: String) : PasskeyException("The passkey wasn't accepted: $why")
    /** No ceremony was started for this session, or it took longer than five minutes. */
    class NoCeremony : PasskeyException("Start again: this passkey request expired")
    /** Adding a passkey needs a session that already passed the second factor (or the very first enrolment). */
    class NotAllowed : PasskeyException("Confirm with your passkey first")
    /** Verifying needs a passkey to exist. */
    class NoneEnrolled : PasskeyException("No passkey is set up yet")
    class BadRecoveryCode : PasskeyException("That recovery code isn't valid")
    /** The session is gone (signed out, expired): sign in again. */
    class SignedOut : PasskeyException("Sign in again")
    /** Too many failed attempts: the session was signed out. */
    class TooManyAttempts : PasskeyException("Too many failed attempts: sign in again")
}

/** Where a signed-in session stands with the second factor. */
enum class SecondFactorStep { ENROL, VERIFY, DONE }

data class PasskeyStatus(
    val step: SecondFactorStep,
    val passkeys: List<Passkey>,
    val recoveryCodesLeft: Int
)

data class Enrolled(val passkey: Passkey, val recoveryCodes: List<String>, val step: SecondFactorStep) {
    override fun toString() = "Enrolled(passkey=${passkey.id}, recoveryCodes=…)"
}

/**
 * Passkeys as the second factor after Google (AOS-617, D-91 layer 2).
 *
 * - A Google sign-in creates a **pending** session. Only the passkey routes accept it; everything else answers
 *   `AUTH_010` until the session is verified by a passkey or a recovery code.
 * - **Bootstrap (no lock-out):** with no passkey enrolled, the pending session may enrol the first one; that verifies
 *   the session and returns ten recovery codes, shown once. The owner is alerted. Later passkeys can only be added
 *   from a verified session.
 * - **Recovery:** a recovery code (80 random bits, stored as SHA-256) verifies a session once and alerts the owner.
 *   With every passkey and code lost, the documented reset is server-side only (docs/security/PASSKEYS.md).
 * - **Limits:** a ceremony lives [ceremonyTtl]; [maxFailures] failed attempts on a session sign it out and alert.
 */
class PasskeyService(
    private val passkeys: PasskeyRepository,
    private val recoveryCodes: RecoveryCodeRepository,
    private val sessions: SessionRepository,
    private val users: UserRepository,
    private val webAuthn: WebAuthnCeremonies,
    private val alerts: SignInAlerts = SignInAlerts(null),
    private val clock: Clock = Clock.systemUTC(),
    private val ceremonyTtl: Duration = Duration.ofMinutes(5),
    private val maxFailures: Int = 5
) {
    private enum class Kind { REGISTER, AUTHENTICATE }
    private data class Pending(val kind: Kind, val requestJson: String, val expiresAt: Instant)

    private val pending = ConcurrentHashMap<UUID, Pending>()
    private val failures = ConcurrentHashMap<UUID, Int>()
    private val random = SecureRandom()

    suspend fun status(userId: UUID, sessionId: UUID): PasskeyStatus {
        val keys = passkeys.findByUser(userId)
        return PasskeyStatus(step(keys, session(userId, sessionId)), keys, recoveryCodes.countUnused(userId))
    }

    /** Creation options for a new passkey. Allowed for the very first passkey, or from a verified session. */
    suspend fun registrationOptions(userId: UUID, sessionId: UUID): String {
        val s = session(userId, sessionId)
        val keys = passkeys.findByUser(userId)
        if (s.secondFactorAt == null && keys.isNotEmpty()) throw PasskeyException.NotAllowed()
        val ceremony = webAuthn.startRegistration(passkeyUser(userId), keys)
        pending[sessionId] = Pending(Kind.REGISTER, ceremony.requestJson, clock.instant().plus(ceremonyTtl))
        return ceremony.optionsJson
    }

    suspend fun register(userId: UUID, sessionId: UUID, responseJson: String, name: String): Enrolled {
        val s = session(userId, sessionId)
        val keys = passkeys.findByUser(userId)
        if (s.secondFactorAt == null && keys.isNotEmpty()) throw PasskeyException.NotAllowed()
        val request = take(sessionId, Kind.REGISTER)
        val who = passkeyUser(userId)
        val credential = guarded(sessionId, userId) { webAuthn.finishRegistration(who, keys, request, responseJson) }
        val now = clock.instant()
        val saved = passkeys.save(Passkey(userId = userId, credentialId = credential.credentialId, publicKeyCose = credential.publicKeyCose,
            signatureCount = credential.signatureCount, name = name.trim().take(60).ifBlank { "Passkey" }, transports = credential.transports, createdAt = now))
        if (s.secondFactorAt == null) sessions.markSecondFactor(sessionId, now)
        val codes = if (keys.isEmpty() || recoveryCodes.countUnused(userId) == 0) newRecoveryCodes(userId) else emptyList()
        alerts.passkeyAdded(userId, saved.name)
        return Enrolled(saved, codes, SecondFactorStep.DONE)
    }

    suspend fun authenticationOptions(userId: UUID, sessionId: UUID): String {
        session(userId, sessionId)
        val keys = passkeys.findByUser(userId).ifEmpty { throw PasskeyException.NoneEnrolled() }
        val ceremony = webAuthn.startAuthentication(passkeyUser(userId), keys)
        pending[sessionId] = Pending(Kind.AUTHENTICATE, ceremony.requestJson, clock.instant().plus(ceremonyTtl))
        return ceremony.optionsJson
    }

    suspend fun authenticate(userId: UUID, sessionId: UUID, responseJson: String) {
        session(userId, sessionId)
        val keys = passkeys.findByUser(userId).ifEmpty { throw PasskeyException.NoneEnrolled() }
        val request = take(sessionId, Kind.AUTHENTICATE)
        val who = passkeyUser(userId)
        val used = guarded(sessionId, userId) { webAuthn.finishAuthentication(who, keys, request, responseJson) }
        val key = keys.first { it.credentialId == used.credentialId }
        val now = clock.instant()
        passkeys.recordUse(key.id, used.signatureCount, now)
        sessions.markSecondFactor(sessionId, now)
        failures.remove(sessionId)
    }

    /** A recovery code instead of a passkey: verifies this session once. @return how many codes are left */
    suspend fun recover(userId: UUID, sessionId: UUID, code: String): Int {
        session(userId, sessionId)
        val normalised = normaliseCode(code)
        val ok = normalised != null && recoveryCodes.consume(userId, sha256(normalised), clock.instant())
        if (!ok) {
            failed(sessionId, userId)
            throw PasskeyException.BadRecoveryCode()
        }
        sessions.markSecondFactor(sessionId, clock.instant())
        failures.remove(sessionId)
        val left = recoveryCodes.countUnused(userId)
        alerts.recoveryCodeUsed(userId, left)
        return left
    }

    /** New recovery codes (the old ones stop working). Only from a verified session. */
    suspend fun regenerateRecoveryCodes(userId: UUID, sessionId: UUID): List<String> {
        if (session(userId, sessionId).secondFactorAt == null) throw PasskeyException.NotAllowed()
        return newRecoveryCodes(userId)
    }

    /** The step a fresh Google sign-in leads to. */
    suspend fun stepAfterSignIn(userId: UUID): SecondFactorStep =
        if (passkeys.findByUser(userId).isEmpty()) SecondFactorStep.ENROL else SecondFactorStep.VERIFY

    private fun step(keys: List<Passkey>, s: Session) = when {
        s.secondFactorAt != null -> SecondFactorStep.DONE
        keys.isEmpty() -> SecondFactorStep.ENROL
        else -> SecondFactorStep.VERIFY
    }

    private suspend fun session(userId: UUID, sessionId: UUID): Session =
        sessions.findById(sessionId)?.takeIf { it.userId == userId && it.isActive } ?: throw PasskeyException.SignedOut()

    private fun take(sessionId: UUID, kind: Kind): String {
        val p = pending.remove(sessionId) ?: throw PasskeyException.NoCeremony()
        if (p.kind != kind || p.expiresAt <= clock.instant()) throw PasskeyException.NoCeremony()
        return p.requestJson
    }

    private suspend fun <T> guarded(sessionId: UUID, userId: UUID, verify: () -> T): T = try {
        verify()
    } catch (e: PasskeyException.Rejected) {
        failed(sessionId, userId)
        throw e
    }

    private suspend fun failed(sessionId: UUID, userId: UUID) {
        val n = failures.merge(sessionId, 1, Int::plus) ?: 1
        if (n >= maxFailures) {
            failures.remove(sessionId)
            pending.remove(sessionId)
            sessions.revoke(sessionId)
            alerts.secondFactorFailed(userId)
            throw PasskeyException.TooManyAttempts()
        }
    }

    private suspend fun passkeyUser(userId: UUID): PasskeyUser {
        val user = users.findById(userId) ?: throw PasskeyException.SignedOut()
        val handle = java.nio.ByteBuffer.allocate(16).putLong(userId.mostSignificantBits).putLong(userId.leastSignificantBits).array()
        return PasskeyUser(handle, user.email, user.name ?: user.email)
    }

    private suspend fun newRecoveryCodes(userId: UUID): List<String> {
        val codes = List(RECOVERY_CODES) { newCode() }
        recoveryCodes.replaceAll(userId, codes.map { sha256(requireNotNull(normaliseCode(it))) }, clock.instant())
        return codes
    }

    /** 16 characters of Crockford base32 (80 bits), shown as XXXX-XXXX-XXXX-XXXX. */
    private fun newCode(): String {
        val chars = CharArray(16) { ALPHABET[random.nextInt(ALPHABET.length)] }
        return chars.concatToString().chunked(4).joinToString("-")
    }

    companion object {
        const val RECOVERY_CODES = 10
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        /** Upper-cased, separators removed, look-alikes folded (O→0, I/L→1); null unless exactly 16 code characters. */
        fun normaliseCode(raw: String): String? {
            val c = raw.uppercase().filter { !it.isWhitespace() && it != '-' }
                .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }.joinToString("")
            return c.takeIf { it.length == 16 && it.all { ch -> ch in ALPHABET } }
        }

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }
    }
}

class InMemoryPasskeyRepository : PasskeyRepository {
    private val store = ConcurrentHashMap<UUID, Passkey>()
    override suspend fun save(passkey: Passkey): Passkey {
        require(store.values.none { it.credentialId == passkey.credentialId }) { "This passkey is already registered" }
        store[passkey.id] = passkey
        return passkey
    }
    override suspend fun findByUser(userId: UUID) = store.values.filter { it.userId == userId }.sortedBy { it.createdAt }
    override suspend fun recordUse(id: UUID, signatureCount: Long, at: Instant) {
        store.computeIfPresent(id) { _, p -> p.copy(signatureCount = signatureCount, lastUsedAt = at) }
    }
}

class InMemoryRecoveryCodeRepository : RecoveryCodeRepository {
    private data class Code(val userId: UUID, val hash: String, val usedAt: Instant?)
    private val codes = ConcurrentHashMap<String, Code>()
    override suspend fun replaceAll(userId: UUID, hashes: List<String>, at: Instant) {
        codes.entries.removeIf { it.value.userId == userId }
        hashes.forEach { codes["$userId:$it"] = Code(userId, it, null) }
    }
    override suspend fun consume(userId: UUID, hash: String, at: Instant): Boolean {
        var won = false
        codes.computeIfPresent("$userId:$hash") { _, c -> if (c.usedAt == null) { won = true; c.copy(usedAt = at) } else c }
        return won
    }
    override suspend fun countUnused(userId: UUID) = codes.values.count { it.userId == userId && it.usedAt == null }
}
