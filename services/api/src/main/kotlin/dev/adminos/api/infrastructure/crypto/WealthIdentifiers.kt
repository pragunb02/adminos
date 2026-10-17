package dev.adminos.api.infrastructure.crypto

import dev.adminos.api.domain.wealth.ProtectedIdentifier
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** What an identifier is. Part of the blind index, so the same digits as a folio and as a PRAN never collide. */
enum class IdentifierKind { FOLIO, DEMAT_BO_ID, BROKER_ACCOUNT, UAN, PRAN, BANK_ACCOUNT }

/**
 * Field encryption for wealth identifiers (AOS-802, D-79, WEALTH-RESEARCH §6.3).
 *
 * - **Keys:** two purpose keys derived with HKDF-SHA-256 (RFC 5869) from `TOKEN_ENCRYPTION_KEY`: one encrypts
 *   (`adminos/wealth/v1/encrypt`), one indexes (`adminos/wealth/v1/index`). The Gmail tokens' key is never used for
 *   wealth data, and the wealth keys can be rotated (a `v2` info string) without touching Gmail tokens or the vault.
 * - **Encryption:** [TokenEncryptor] v2 (AES-256-GCM, random 96-bit IV) under the wealth key, with the row and field
 *   as associated data, e.g. `investment_accounts:<id>:identifier`: a ciphertext copied to another row fails.
 * - **Lookup:** a blind index, HMAC-SHA-256 over `kind:normalised value` with the index key. Equality only; the
 *   index key never leaves the process, so the index can't be brute-forced from a database copy (folio numbers
 *   are short and guessable, which is why a plain hash would not do).
 * - **Display:** the last 4 characters only.
 */
class WealthIdentifiers(masterKeyBase64: String) {

    private val encryptor: TokenEncryptor
    private val indexKey: SecretKeySpec

    init {
        val master = runCatching { Base64.getDecoder().decode(masterKeyBase64.trim()) }.getOrNull()
        require(master != null && master.size == 32) { TokenEncryptor.KEY_HELP }
        val prk = Hkdf.extract(SALT, master)
        encryptor = TokenEncryptor(Base64.getEncoder().encodeToString(Hkdf.expand(prk, ENCRYPT_INFO, 32)))
        indexKey = SecretKeySpec(Hkdf.expand(prk, INDEX_INFO, 32), HMAC)
    }

    /** Encrypts [raw] for the row field [context] and computes its index. [raw] is validated and normalised first. */
    fun protect(kind: IdentifierKind, raw: String, context: String): ProtectedIdentifier {
        val value = normalise(raw)
        return ProtectedIdentifier(encryptor.encrypt(value, context), indexOf(kind, value), value.takeLast(4).takeIf { value.length > 4 } ?: "")
    }

    /** The blind index for looking up [raw] (same normalisation as [protect]). */
    fun index(kind: IdentifierKind, raw: String): String = indexOf(kind, normalise(raw))

    /** The plain identifier. Throws if the key, the [context] or the bytes don't match (tampering is never accepted). */
    fun reveal(identifier: ProtectedIdentifier, context: String): String = encryptor.decrypt(identifier.ciphertext, context)

    private fun indexOf(kind: IdentifierKind, normalised: String): String {
        val mac = Mac.getInstance(HMAC)
        mac.init(indexKey)
        return HexFormat.of().formatHex(mac.doFinal("${kind.name}:$normalised".toByteArray(Charsets.UTF_8)))
    }

    companion object {
        private const val HMAC = "HmacSHA256"
        private val SALT = "adminos-wealth-keys".toByteArray(Charsets.UTF_8)
        private val ENCRYPT_INFO = "adminos/wealth/v1/encrypt".toByteArray(Charsets.UTF_8)
        private val INDEX_INFO = "adminos/wealth/v1/index".toByteArray(Charsets.UTF_8)
        private val ALLOWED = Regex("[A-Z0-9/-]{1,40}")

        /**
         * Upper case, whitespace removed ("12345678 / 90" and "12345678/90" are the same folio). Only letters,
         * digits, `/` and `-`, at most 40 characters: anything else is refused, so hostile text never gets stored.
         */
        fun normalise(raw: String): String {
            val v = raw.filterNot { it.isWhitespace() }.uppercase()
            require(v.matches(ALLOWED)) { "an identifier is 1–40 letters, digits, '/' or '-'" }
            return v
        }
    }
}

/** HKDF-SHA-256 (RFC 5869), from the JDK's HMAC. */
internal object Hkdf {
    private const val HMAC = "HmacSHA256"
    private const val HASH_LEN = 32

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_LEN) { "HKDF output length out of range" }
        val out = java.io.ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            out.write(previous)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        return mac.doFinal(data)
    }
}
