package dev.adminos.api.domain.documents

import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

class DocumentNotFoundException : RuntimeException("Document not found")
class UnsupportedDocumentTypeException : RuntimeException("Only PDF, PNG, JPEG, WebP and HEIC files can be stored")
class DocumentTooLargeException : RuntimeException("A document can be at most 20 MB")
class EmptyDocumentException : RuntimeException("The file is empty")

data class DocumentUpload(
    val bytes: ByteArray,
    val originalName: String?,
    val title: String?,
    val category: DocumentCategory,
    val expiresOn: LocalDate?,
    val notes: String?
)

/** A metadata change; null fields stay as they are, [clearExpiry]/[clearNotes] remove them. */
data class DocumentPatch(
    val title: String? = null,
    val category: DocumentCategory? = null,
    val expiresOn: LocalDate? = null,
    val clearExpiry: Boolean = false,
    val notes: String? = null,
    val clearNotes: Boolean = false
)

data class DocumentFile(val bytes: ByteArray, val type: DocumentFileType, val downloadName: String)

/**
 * The documents vault (AOS-507, D-60). Every file is encrypted here, with its own key, before it
 * leaves the app; storage only ever holds ciphertext. Only the owner can see, download, change or
 * delete a document (others get "not found", never "forbidden", so ids reveal nothing).
 *
 * **Failures leave no half-state:** the encrypted file is written first and the row second; if the
 * row fails, the file is removed again. Deleting removes the file first, then the row.
 */
class DocumentService(
    private val documents: DocumentRepository,
    private val storage: DocumentStorage,
    private val sealer: DocumentSealer,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = LoggerFactory.getLogger(DocumentService::class.java)

    suspend fun upload(userId: UUID, upload: DocumentUpload): Document {
        if (upload.bytes.isEmpty()) throw EmptyDocumentException()
        if (upload.bytes.size > Document.MAX_BYTES) throw DocumentTooLargeException()
        val type = DocumentFileType.detect(upload.bytes) ?: throw UnsupportedDocumentTypeException()
        val id = UUID.randomUUID()
        val name = upload.originalName?.let(::safeName)
        val title = upload.title?.trim()?.takeIf { it.isNotEmpty() } ?: name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Document"
        val sealed = sealer.seal(id, upload.bytes)
        val now = clock.instant()
        val document = Document(
            id = id, userId = userId, title = title.take(Document.MAX_TITLE), category = upload.category, fileType = type,
            sizeBytes = upload.bytes.size.toLong(), sha256 = sha256(upload.bytes), wrappedKey = sealed.wrappedKey,
            storageKey = "documents/$id", originalName = name, expiresOn = upload.expiresOn,
            notes = upload.notes?.trim()?.takeIf { it.isNotEmpty() }, createdAt = now, updatedAt = now
        )
        storage.put(document.storageKey, sealed.ciphertext)
        try {
            documents.save(document)
        } catch (e: Exception) {
            runCatching { storage.delete(document.storageKey) }.onFailure { logger.warn("Couldn't remove the orphaned file for document {}", id, it) }
            throw e
        }
        logger.info("Document {} stored ({}, {} bytes)", id, type, document.sizeBytes)
        return document
    }

    /** The user's documents, newest first, optionally one category or matching [query] in title, notes or file name. */
    suspend fun list(userId: UUID, category: DocumentCategory? = null, query: String? = null): List<Document> {
        val q = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return documents.findByUserId(userId).filter { d ->
            (category == null || d.category == category) &&
                (q == null || listOfNotNull(d.title, d.notes, d.originalName).any { q in it.lowercase() })
        }
    }

    suspend fun get(userId: UUID, id: UUID): Document = documents.findById(id)?.takeIf { it.userId == userId } ?: throw DocumentNotFoundException()

    /** Fetches, decrypts and checks the file. */
    suspend fun download(userId: UUID, id: UUID): DocumentFile {
        val d = get(userId, id)
        val plaintext = sealer.open(d.id, storage.get(d.storageKey), d.wrappedKey)
        if (sha256(plaintext) != d.sha256) throw DocumentIntegrityException("Document ${d.id} doesn't match its checksum")
        val base = (d.originalName?.substringBeforeLast('.') ?: d.title).let(::safeName).ifBlank { "document" }
        return DocumentFile(plaintext, d.fileType, "$base.${d.fileType.extension}")
    }

    suspend fun update(userId: UUID, id: UUID, patch: DocumentPatch): Document {
        val d = get(userId, id)
        val expiresOn = if (patch.clearExpiry) null else patch.expiresOn ?: d.expiresOn
        return documents.update(d.copy(
            title = patch.title?.trim()?.takeIf { it.isNotEmpty() } ?: d.title,
            category = patch.category ?: d.category,
            expiresOn = expiresOn,
            expiryRemindersSent = if (expiresOn != d.expiresOn) emptySet() else d.expiryRemindersSent,
            notes = if (patch.clearNotes) null else patch.notes?.trim()?.takeIf { it.isNotEmpty() } ?: d.notes
        ))
    }

    suspend fun delete(userId: UUID, id: UUID) {
        val d = get(userId, id)
        storage.delete(d.storageKey)
        documents.delete(d.id)
        logger.info("Document {} deleted", id)
    }

    companion object {
        internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** A file name safe for headers and disks: no paths, quotes or control characters, at most 100 chars. */
        internal fun safeName(raw: String): String = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}\"<>:|?*]"), "").trim().take(100)
    }
}
