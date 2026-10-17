package dev.adminos.api.domain.documents

import dev.adminos.api.domain.identity.InMemoryUserRepository
import dev.adminos.api.domain.identity.User
import dev.adminos.api.domain.identity.UserCalendar
import dev.adminos.api.domain.notifications.InMemoryNotificationPreferencesRepository
import dev.adminos.api.domain.notifications.InMemoryNotificationRepository
import dev.adminos.api.domain.notifications.NotificationType
import dev.adminos.api.domain.notifications.notificationService
import dev.adminos.api.infrastructure.crypto.TokenEncryptor
import dev.adminos.api.infrastructure.documents.AesGcmDocumentSealer
import dev.adminos.api.infrastructure.documents.InMemoryDocumentStorage
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The documents vault (AOS-507): file types, envelope encryption, the service, expiry reminders. */
class DocumentsTest {
    /** A per-run key, never a literal. */
    private val appKey = TokenEncryptor(Base64.getEncoder().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes)))
    private val sealer = AesGcmDocumentSealer(appKey)
    private val storage = InMemoryDocumentStorage()
    private val repo = InMemoryDocumentRepository()
    private val service = DocumentService(repo, storage, sealer)
    private val userId = UUID.randomUUID()

    private val pdf = "%PDF-1.7\n1 0 obj << /Type /Catalog >> endobj\nForm 16 for FY 2025-26".toByteArray()
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(20) { it.toByte() }

    private fun upload(bytes: ByteArray = pdf, name: String? = "Form16_FY26.pdf", title: String? = null, category: DocumentCategory = DocumentCategory.TAX,
                       expires: LocalDate? = null, notes: String? = null) = DocumentUpload(bytes, name, title, category, expires, notes)

    // ── File types ──

    @Test
    fun `files are recognised by their first bytes`() {
        assertEquals(DocumentFileType.PDF, DocumentFileType.detect(pdf))
        assertEquals(DocumentFileType.PNG, DocumentFileType.detect(png))
        assertEquals(DocumentFileType.JPEG, DocumentFileType.detect(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals(DocumentFileType.WEBP, DocumentFileType.detect("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        assertEquals(DocumentFileType.HEIC, DocumentFileType.detect(byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray()))
        assertNull(DocumentFileType.detect("MZ executable".toByteArray()))
        assertNull(DocumentFileType.detect(byteArrayOf(0x25)))
    }

    // ── Envelope encryption ──

    @Test
    fun `a file seals and opens, and storage never sees the plaintext`() {
        val id = UUID.randomUUID()
        val sealed = sealer.seal(id, pdf)
        assertFalse(String(sealed.ciphertext, Charsets.ISO_8859_1).contains("Form 16"))
        assertContentEquals(pdf, sealer.open(id, sealed.ciphertext, sealed.wrappedKey))
        val again = sealer.seal(id, pdf)
        assertFalse(sealed.ciphertext.contentEquals(again.ciphertext), "a fresh key and nonce every time")
    }

    @Test
    fun `tampered bytes, another document's id, a wrong key or a broken format are refused`() {
        val id = UUID.randomUUID()
        val sealed = sealer.seal(id, pdf)
        val flipped = sealed.ciphertext.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<DocumentIntegrityException> { sealer.open(id, flipped, sealed.wrappedKey) }
        assertFailsWith<DocumentIntegrityException> { sealer.open(UUID.randomUUID(), sealed.ciphertext, sealed.wrappedKey) }
        assertFailsWith<DocumentIntegrityException> { sealer.open(id, sealed.ciphertext, sealer.seal(UUID.randomUUID(), pdf).wrappedKey) }
        assertFailsWith<DocumentIntegrityException> { sealer.open(id, byteArrayOf(1, 2, 3), sealed.wrappedKey) }
        assertFailsWith<DocumentIntegrityException> { sealer.open(id, byteArrayOf(9) + sealed.ciphertext.drop(1), sealed.wrappedKey) }
        assertFailsWith<DocumentIntegrityException> { sealer.open(id, sealed.ciphertext, "v2:not-base64!") }
        assertFailsWith<DocumentIntegrityException> { sealer.open(id, sealed.ciphertext, "legacy-format") }
    }

    // ── Upload, list, download ──

    @Test
    fun `an upload stores the encrypted file and a row, titled from the file name`() = runBlocking<Unit> {
        val d = service.upload(userId, upload())
        assertEquals("Form16_FY26", d.title)
        assertEquals(DocumentFileType.PDF, d.fileType)
        assertEquals(pdf.size.toLong(), d.sizeBytes)
        assertEquals("documents/${d.id}", d.storageKey)
        assertFalse(storage.get(d.storageKey).contentEquals(pdf))
        assertEquals(listOf(d), service.list(userId))
        val file = service.download(userId, d.id)
        assertContentEquals(pdf, file.bytes)
        assertEquals("Form16_FY26.pdf", file.downloadName)
    }

    @Test
    fun `the type comes from the bytes, not the name, and bad files are refused`() = runBlocking<Unit> {
        assertEquals(DocumentFileType.PNG, service.upload(userId, upload(png, name = "scan.pdf")).fileType)
        assertFailsWith<UnsupportedDocumentTypeException> { service.upload(userId, upload("#!/bin/sh".toByteArray(), name = "x.pdf")) }
        assertFailsWith<EmptyDocumentException> { service.upload(userId, upload(ByteArray(0))) }
        assertFailsWith<DocumentTooLargeException> { service.upload(userId, upload(pdf + ByteArray(Document.MAX_BYTES.toInt()))) }
    }

    @Test
    fun `names are made safe, and a title or notes can be given`() = runBlocking<Unit> {
        val d = service.upload(userId, upload(name = "../../etc/\"passwd\".pdf", title = "  PAN card  ", notes = "  front side "))
        assertEquals("passwd.pdf", d.originalName)
        assertEquals("PAN card", d.title)
        assertEquals("front side", d.notes)
        assertEquals("Document", service.upload(userId, upload(name = null)).title)
        assertEquals("passwd.pdf", service.download(userId, d.id).downloadName)
    }

    @Test
    fun `if storage fails nothing is saved, and if the row fails the file is removed again`() = runBlocking<Unit> {
        val failingStorage = object : DocumentStorage by storage {
            override suspend fun put(key: String, bytes: ByteArray) = throw DocumentStorageException("R2 down")
        }
        assertFailsWith<DocumentStorageException> { DocumentService(repo, failingStorage, sealer).upload(userId, upload()) }
        assertTrue(repo.findByUserId(userId).isEmpty())

        var storedKey: String? = null
        val recording = object : DocumentStorage by storage {
            override suspend fun put(key: String, bytes: ByteArray) { storedKey = key; storage.put(key, bytes) }
        }
        val failingRepo = object : DocumentRepository by repo {
            override suspend fun save(document: Document): Document = throw IOException("db down")
        }
        assertFailsWith<IOException> { DocumentService(failingRepo, recording, sealer).upload(userId, upload()) }
        assertFailsWith<DocumentObjectMissingException> { storage.get(storedKey!!) }
    }

    @Test
    fun `only the owner can see, download, change or delete a document`() = runBlocking<Unit> {
        val d = service.upload(userId, upload())
        val other = UUID.randomUUID()
        assertTrue(service.list(other).isEmpty())
        assertFailsWith<DocumentNotFoundException> { service.get(other, d.id) }
        assertFailsWith<DocumentNotFoundException> { service.download(other, d.id) }
        assertFailsWith<DocumentNotFoundException> { service.update(other, d.id, DocumentPatch(title = "x")) }
        assertFailsWith<DocumentNotFoundException> { service.delete(other, d.id) }
        assertFailsWith<DocumentNotFoundException> { service.get(userId, UUID.randomUUID()) }
    }

    @Test
    fun `list filters by category and searches title, notes and file name`() = runBlocking<Unit> {
        service.upload(userId, upload(title = "Form 16"))
        service.upload(userId, upload(png, name = "car-rc.png", category = DocumentCategory.VEHICLE, notes = "Registration certificate"))
        assertEquals(listOf("car-rc"), service.list(userId, DocumentCategory.VEHICLE).map { it.title })
        assertEquals(listOf("car-rc"), service.list(userId, query = "REGISTRATION").map { it.title })
        assertEquals(listOf("Form 16"), service.list(userId, query = "form16").map { it.title })
        assertEquals(2, service.list(userId, query = " ").size)
    }

    @Test
    fun `a file that doesn't match its checksum is refused`() = runBlocking<Unit> {
        val d = service.upload(userId, upload())
        repo.update(d.copy(sha256 = "0".repeat(64)))
        assertFailsWith<DocumentIntegrityException> { service.download(userId, d.id) }
    }

    @Test
    fun `edits change only what's given, can clear expiry and notes, and a new expiry resets its reminders`() = runBlocking<Unit> {
        val d = service.upload(userId, upload(expires = LocalDate.of(2027, 1, 1), notes = "old"))
        repo.update(d.copy(expiryRemindersSent = setOf(30)))
        val renamed = service.update(userId, d.id, DocumentPatch(title = "Passport", category = DocumentCategory.IDENTITY))
        assertEquals("Passport", renamed.title); assertEquals(DocumentCategory.IDENTITY, renamed.category)
        assertEquals(LocalDate.of(2027, 1, 1), renamed.expiresOn); assertEquals(setOf(30), renamed.expiryRemindersSent)
        val moved = service.update(userId, d.id, DocumentPatch(expiresOn = LocalDate.of(2036, 1, 1)))
        assertTrue(moved.expiryRemindersSent.isEmpty())
        val cleared = service.update(userId, d.id, DocumentPatch(clearExpiry = true, clearNotes = true, title = " "))
        assertNull(cleared.expiresOn); assertNull(cleared.notes); assertEquals("Passport", cleared.title)
    }

    @Test
    fun `delete removes the file and the row, and if storage fails the row stays`() = runBlocking<Unit> {
        val d = service.upload(userId, upload())
        val failing = DocumentService(repo, object : DocumentStorage by storage {
            override suspend fun delete(key: String) = throw DocumentStorageException("R2 down")
        }, sealer)
        assertFailsWith<DocumentStorageException> { failing.delete(userId, d.id) }
        assertEquals(d.id, repo.findById(d.id)?.id)
        service.delete(userId, d.id)
        assertNull(repo.findById(d.id))
        assertFailsWith<DocumentObjectMissingException> { storage.get(d.storageKey) }
    }

    @Test
    fun `documents validate themselves`() {
        val ok = Document(userId = userId, title = "x", category = DocumentCategory.OTHER, fileType = DocumentFileType.PDF, sizeBytes = 1,
            sha256 = "a".repeat(64), wrappedKey = "k", storageKey = "documents/x")
        assertFailsWith<IllegalArgumentException> { ok.copy(title = " ") }
        assertFailsWith<IllegalArgumentException> { ok.copy(notes = "x".repeat(1001)) }
        assertFailsWith<IllegalArgumentException> { ok.copy(sizeBytes = 0) }
        assertFailsWith<IllegalArgumentException> { ok.copy(sha256 = "xyz") }
    }

    // ── Expiry reminders ──

    @Test
    fun `expiry reminders go 30, 7 and 1 days before, once each, on the user's date`() = runBlocking<Unit> {
        val now = Instant.parse("2026-10-16T06:30:00Z")
        val users = InMemoryUserRepository()
        val owner = users.save(User(email = "p@example.com", googleId = "g"))
        val calendar = UserCalendar(users, Clock.fixed(now, ZoneId.of("UTC")))
        val notifications = InMemoryNotificationRepository()
        val job = DocumentExpiryJob(repo, notificationService(InMemoryNotificationPreferencesRepository(), notifications), calendar, ZoneId.of("Asia/Kolkata"))
        val passport = service.upload(owner.id, upload(title = "Passport", category = DocumentCategory.IDENTITY, expires = LocalDate.of(2026, 11, 15)))
        service.upload(owner.id, upload(title = "Insurance", expires = LocalDate.of(2026, 11, 1)))

        assertEquals(mapOf("remindersSent" to 1, "failed" to 0), job.run().counts)
        val n = notifications.findByUserAndType(owner.id, NotificationType.GENERAL).single()
        assertEquals("Passport expires in 30 days", n.title)
        assertEquals("It expires on 2026-11-15. Time to renew it.", n.body)
        assertEquals(mapOf("entity_type" to "document", "entity_id" to passport.id.toString(), "action" to "view_document"), n.data)
        assertEquals(mapOf("remindersSent" to 0, "failed" to 0), job.run().counts, "once")
        assertFalse(job.remind(passport.copy(expiresOn = null)))
        assertTrue(job.remind(passport.copy(expiresOn = LocalDate.of(2026, 10, 17))), "1 day, singular")
        assertEquals("Passport expires in 1 day", notifications.findByUserAndType(owner.id, NotificationType.GENERAL).maxBy { it.createdAt }.title)
    }
}
