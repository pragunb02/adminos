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

    @Test
    fun `confirmed fields round-trip and the database refuses a full number or a bad amount`() = runBlocking<Unit> {
        val user = PostgresTestDatabase.newUser()
        val d = repo.save(Document(userId = user, title = "Motor policy", category = DocumentCategory.INSURANCE, fileType = DocumentFileType.PDF,
            sizeBytes = 10, sha256 = "b".repeat(64), wrappedKey = "v2:wrapped", storageKey = "documents/${java.util.UUID.randomUUID()}",
            createdAt = Instant.parse("2026-10-16T06:00:00Z"), updatedAt = Instant.parse("2026-10-16T06:00:00Z")))
        repo.update(d.copy(issuer = "Example Mutual General Insurance Company Limited", numberLast4 = "AB12", amount = java.math.BigDecimal("12500.00")))
        val back = repo.findById(d.id)!!
        assertEquals("AB12", back.numberLast4)
        assertEquals(java.math.BigDecimal("12500.00"), back.amount)
        assertEquals("Example Mutual General Insurance Company Limited", back.issuer)
        // V80's checks hold even if the domain's were bypassed: no full number, no zero or negative amount.
        dev.adminos.api.infrastructure.database.dbQuery {
            for (sql in listOf("UPDATE documents SET number_last4 = '98765' WHERE id = '${d.id}'", "UPDATE documents SET number_last4 = 'ab12' WHERE id = '${d.id}'",
                    "UPDATE documents SET amount = 0 WHERE id = '${d.id}'")) {
                kotlin.test.assertFails(sql) {
                    org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.current().exec("SAVEPOINT s"); try {
                        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.current().exec(sql)
                    } finally { org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.current().exec("ROLLBACK TO SAVEPOINT s") }
                }
            }
        }
    }
}
