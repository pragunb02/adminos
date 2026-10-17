package dev.adminos.api.domain.documents

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** What a document is for (AOS-507): used for grouping and search. */
enum class DocumentCategory { IDENTITY, TAX, INSURANCE, BANK, PROPERTY, VEHICLE, MEDICAL, WARRANTY, OTHER }

/** File types the vault accepts, recognised by their first bytes (never by the name or the browser's word). */
enum class DocumentFileType(val mime: String, val extension: String) {
    PDF("application/pdf", "pdf"),
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    WEBP("image/webp", "webp"),
    /** iPhone photos. */
    HEIC("image/heic", "heic");

    companion object {
        fun detect(bytes: ByteArray): DocumentFileType? {
            fun at(offset: Int, vararg b: Int) = bytes.size >= offset + b.size && b.indices.all { bytes[offset + it] == b[it].toByte() }
            fun ascii(offset: Int, s: String) = at(offset, *s.map { it.code }.toIntArray())
            return when {
                ascii(0, "%PDF-") -> PDF
                at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> PNG
                at(0, 0xFF, 0xD8, 0xFF) -> JPEG
                ascii(0, "RIFF") && ascii(8, "WEBP") -> WEBP
                ascii(4, "ftyp") && listOf("heic", "heix", "hevc", "mif1", "msf1").any { ascii(8, it) } -> HEIC
                else -> null
            }
        }
    }
}

/**
 * A file in the user's vault (AOS-507, D-60). The file itself lives encrypted in object storage under
 * [storageKey] (no name or user detail in the key); this row holds what's needed to find, show and
 * decrypt it.
 */
data class Document(
    val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    val title: String,
    val category: DocumentCategory,
    val fileType: DocumentFileType,
    val sizeBytes: Long,
    /** SHA-256 of the original bytes, hex: checked on every download. */
    val sha256: String,
    /** The file's own key, encrypted with the app key (TokenEncryptor, context "document:<id>"). */
    val wrappedKey: String,
    val storageKey: String,
    val originalName: String? = null,
    val expiresOn: LocalDate? = null,
    val notes: String? = null,
    /** "Days before expiry" reminders sent for [expiresOn] (reset when the date changes). */
    val expiryRemindersSent: Set<Int> = emptySet(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {
    init {
        require(title.isNotBlank() && title.length <= MAX_TITLE) { "title: 1–$MAX_TITLE characters" }
        require(notes == null || notes.length <= MAX_NOTES) { "notes: at most $MAX_NOTES characters" }
        require(sizeBytes in 1..MAX_BYTES) { "A document is 1 byte to 20 MB" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "sha256 must be 64 hex characters" }
    }

    companion object {
        const val MAX_BYTES = 20L * 1024 * 1024
        const val MAX_TITLE = 120
        const val MAX_NOTES = 1000
    }
}

interface DocumentRepository {
    suspend fun save(document: Document): Document
    suspend fun update(document: Document): Document
    suspend fun findById(id: UUID): Document?
    suspend fun findByUserId(userId: UUID): List<Document>
    suspend fun delete(id: UUID)
    /** Documents of every user expiring in [from]..[to] (expiry reminders). */
    suspend fun findExpiringBetween(from: LocalDate, to: LocalDate): List<Document>
}

class InMemoryDocumentRepository : DocumentRepository {
    private val store = java.util.concurrent.ConcurrentHashMap<UUID, Document>()
    override suspend fun save(document: Document) = document.also { store[it.id] = it }
    override suspend fun update(document: Document) = document.copy(updatedAt = Instant.now()).also { store[it.id] = it }
    override suspend fun findById(id: UUID) = store[id]
    override suspend fun findByUserId(userId: UUID) = store.values.filter { it.userId == userId }.sortedByDescending { it.createdAt }
    override suspend fun delete(id: UUID) { store.remove(id) }
    override suspend fun findExpiringBetween(from: LocalDate, to: LocalDate) =
        store.values.filter { d -> d.expiresOn?.let { it >= from && it <= to } == true }.sortedBy { it.expiresOn }
}
