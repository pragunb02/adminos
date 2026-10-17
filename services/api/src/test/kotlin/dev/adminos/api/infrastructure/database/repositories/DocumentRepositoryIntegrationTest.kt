package dev.adminos.api.infrastructure.database.repositories

import dev.adminos.api.domain.documents.*
import dev.adminos.api.infrastructure.database.PostgresTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** V23 documents on Postgres (AOS-507). */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentRepositoryIntegrationTest {
    private val repo = ExposedDocumentRepository()

    @BeforeAll
    fun start() = PostgresTestDatabase.ensureStarted()

    @Test
    fun `a document round-trips, edits keep the file fields, and expiry is queryable`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val d = repo.save(Document(userId = user, title = "Passport", category = DocumentCategory.IDENTITY, fileType = DocumentFileType.PDF,
            sizeBytes = 1234, sha256 = "a".repeat(64), wrappedKey = "v2:wrapped", storageKey = "documents/${java.util.UUID.randomUUID()}",
            originalName = "passport.pdf", expiresOn = LocalDate.of(2031, 5, 1), notes = "front + back",
            createdAt = Instant.parse("2026-10-16T06:00:00Z"), updatedAt = Instant.parse("2026-10-16T06:00:00Z")))
        assertEquals(d, repo.findById(d.id))
        repo.update(d.copy(title = "My passport", expiryRemindersSent = setOf(30, 7), sizeBytes = 99, storageKey = "ignored"))
        val back = repo.findById(d.id)!!
        assertEquals("My passport", back.title)
        assertEquals(setOf(7, 30), back.expiryRemindersSent)
        assertEquals(1234, back.sizeBytes, "the file's own fields never change after upload")
        assertEquals(d.storageKey, back.storageKey)
        assertEquals(listOf(d.id), repo.findByUserId(user).map { it.id })
        assertEquals(listOf(d.id), repo.findExpiringBetween(LocalDate.of(2031, 4, 1), LocalDate.of(2031, 6, 1)).filter { it.userId == user }.map { it.id })
        repo.delete(d.id)
        assertNull(repo.findById(d.id))
    }
}
